#include "campus_ble.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_hs_adv.h"
#include "host/ble_hs_mbuf.h"
#include "host/ble_store.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "campus_frame.h"
#include "campus_model.h"
#include "campus_snapshot.h"

static const char *TAG = "campus_ble";

// 沿用 Nordic UART 布局。用字符串交给 ble_uuid_from_str 解析，而不是手写
// BLE_UUID128_INIT 的字节数组 —— 那个是反序的，手算错一位手机就永远发现不了服务，
// 而这一步没有硬件可验。
#define UUID_SERVICE "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define UUID_RX      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define UUID_TX      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

#define DEVICE_NAME "CampusTodo"

// 单次写的上限：MTU(256) - ATT 头(3) = 253，取 260 留余量。
#define WRITE_MAX 260
// 通知载荷上限：ACK 是 3 字节，OP 是几十字节，这里给足。
#define NOTIFY_MAX 64

// host 任务栈：不止跑协议栈，快照解析与 NVS 落盘都在这条路径上（NVS 提交约 2KB 栈）。
#define HOST_TASK_STACK 6144

// 16KB，走 .bss。BLE 起来后堆只剩约 125KB，能用静态的就不用堆。
static campus_assembler_t s_assembler;
static campus_list_t *s_target;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_tx_val_handle;
static uint8_t s_addr_type;
static bool s_started;
static uint16_t s_dev_seq;

// 发送路径的互斥锁。ACK 由 host 任务发、OP 由输入任务发，两条路都可能同时
// 调 ble_gatts_notify_custom；并发时的失败返回很含糊，而且几乎无法复现。
static SemaphoreHandle_t s_tx_lock;

static campus_ble_passkey_cb_t s_passkey_cb;
static campus_ble_snapshot_cb_t s_snapshot_cb;
static campus_ble_link_cb_t s_link_cb;

static ble_uuid_any_t s_svc_uuid;
static ble_uuid_any_t s_rx_uuid;
static ble_uuid_any_t s_tx_uuid;
static struct ble_gatt_chr_def s_chrs[3];
static struct ble_gatt_svc_def s_svcs[2];

static int gap_event(struct ble_gap_event *event, void *arg);
static void resolve_tx_handle(void);

// ------------------------------------------------------------------ 发送

static void notify_frame(uint8_t type, uint16_t transfer_id, const uint8_t *payload, uint16_t len) {
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_tx_val_handle == 0) {
        return;
    }
    if (len > NOTIFY_MAX) {
        ESP_LOGW(TAG, "通知载荷过长 (%u)", (unsigned)len);
        return;
    }
    uint8_t raw[CAMPUS_FRAME_HEADER + NOTIFY_MAX];
    size_t n = campus_frame_encode(type, transfer_id, 0, 1,
                                   campus_crc16(payload, len), payload, len, raw, sizeof(raw));
    if (n == 0) {
        return;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(raw, (uint16_t)n);
    if (om == NULL) {
        ESP_LOGW(TAG, "分配 mbuf 失败");
        return;
    }
    if (s_tx_lock != NULL) {
        xSemaphoreTake(s_tx_lock, portMAX_DELAY);
    }
    int rc = ble_gatts_notify_custom(s_conn_handle, s_tx_val_handle, om);
    if (s_tx_lock != NULL) {
        xSemaphoreGive(s_tx_lock);
    }
    if (rc != 0) {
        ESP_LOGW(TAG, "发送通知失败: %d", rc);
    }
}

void campus_ble_send_ack(uint16_t transfer_id, uint8_t status) {
    uint8_t ack[3] = {
        (uint8_t)(transfer_id & 0xFF),
        (uint8_t)((transfer_id >> 8) & 0xFF),
        status,
    };
    notify_frame(CAMPUS_TYPE_ACK, transfer_id, ack, sizeof(ack));
}

static void send_op_now(const campus_op_t *op) {
    char text[64];
    uint16_t seq = ++s_dev_seq;
    int n = snprintf(text, sizeof(text), "%u\t%s\t%u\t%u",
                     (unsigned)op->todo_id, campus_op_name(op->action),
                     (unsigned)op->snooze_minutes, (unsigned)seq);
    if (n <= 0 || n >= (int)sizeof(text)) {
        return;
    }
    // transfer_id 用 dev_seq：手机回 ACK 时带的就是这个号，设备靠它对上号。
    notify_frame(CAMPUS_TYPE_OP, seq, (const uint8_t *)text, (uint16_t)n);
    ESP_LOGI(TAG, "回传操作 #%u todo=%u %s", (unsigned)seq,
             (unsigned)op->todo_id, campus_op_name(op->action));
}

void campus_ble_send_op(uint32_t todo_id, campus_op_action_t action, uint16_t snooze_minutes) {
    // 发送路径由 s_tx_lock 串行化（见 notify_frame），所以可以从输入任务直接调用，
    // 不需要再绕一个队列和一个专属任务。
    const campus_op_t op = {
        .todo_id = todo_id,
        .action = action,
        .snooze_minutes = snooze_minutes,
        .dev_seq = 0,
    };
    send_op_now(&op);
}

// -------------------------------------------------------------- 接收与重组

static void handle_complete(uint8_t type, uint16_t transfer_id) {
    if (type == CAMPUS_TYPE_ACK) {
        // 关键：ACK 不能回 ACK。不判断的话两边会互相确认个没完。
        uint8_t status = s_assembler.len >= 3 ? s_assembler.data[2] : 0xFF;
        ESP_LOGI(TAG, "手机确认收到 (transfer=%u status=%u)", (unsigned)transfer_id, status);
        return;
    }
    if (type != CAMPUS_TYPE_SNAPSHOT) {
        ESP_LOGW(TAG, "收到未处理的帧类型 0x%02x", type);
        campus_ble_send_ack(transfer_id, CAMPUS_ACK_PARSE_FAIL);
        return;
    }
    if (s_target == NULL) {
        ESP_LOGE(TAG, "没有设置解析目标缓冲");
        campus_ble_send_ack(transfer_id, CAMPUS_ACK_PARSE_FAIL);
        return;
    }

    // 解析就地改写缓冲（把分隔符换成 '\0'），必须在本次回调返回前完成 ——
    // 也因此不存在「解析到一半被下一片覆盖」的窗口。
    campus_snapshot_result_t parsed =
            campus_snapshot_parse((char *)s_assembler.data, s_assembler.len, s_target);
    if (parsed.error != NULL) {
        ESP_LOGW(TAG, "快照解析失败: %s", parsed.error);
        campus_ble_send_ack(transfer_id, CAMPUS_ACK_PARSE_FAIL);
        return;
    }

    ESP_LOGI(TAG, "收到快照: %u 条 (跳过 %u)", parsed.parsed, parsed.skipped);

    // 先落盘、再确认。落盘失败就让手机重传，而不是假装成功。
    bool stored = s_snapshot_cb == NULL ? true : s_snapshot_cb(s_target, parsed.parsed, parsed.skipped);
    campus_ble_send_ack(transfer_id, stored ? CAMPUS_ACK_OK : CAMPUS_ACK_PARSE_FAIL);
}

static int rx_access(uint16_t conn_handle, uint16_t attr_handle,
                     struct ble_gatt_access_ctxt *ctxt, void *arg) {
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0 || len > WRITE_MAX) {
        ESP_LOGW(TAG, "写入长度异常: %u", (unsigned)len);
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    uint8_t buffer[WRITE_MAX];
    if (os_mbuf_copydata(ctxt->om, 0, len, buffer) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    campus_frame_t frame;
    if (!campus_frame_decode(buffer, len, &frame)) {
        // 不是本协议的帧就忽略：BLE 调试工具会往特征里写各种东西，不该因此断连。
        ESP_LOGW(TAG, "忽略无法识别的帧 (%u 字节)", (unsigned)len);
        return 0;
    }

    switch (campus_assembler_offer(&s_assembler, &frame)) {
        case CAMPUS_ASM_NEED_MORE:
            return 0;
        case CAMPUS_ASM_DUPLICATE:
            return 0;  // 重传：忽略，不重复计数
        case CAMPUS_ASM_OUT_OF_ORDER:
            ESP_LOGW(TAG, "分片跳号: 收到 index=%u，期望 %u",
                     (unsigned)frame.index, (unsigned)s_assembler.next_index);
            return 0;
        case CAMPUS_ASM_TOO_LARGE:
            ESP_LOGW(TAG, "快照超出重组缓冲");
            campus_ble_send_ack(frame.transfer_id, CAMPUS_ACK_PARSE_FAIL);
            return 0;
        case CAMPUS_ASM_CRC_FAIL:
            ESP_LOGW(TAG, "整包 CRC 不符 (transfer=%u)", (unsigned)frame.transfer_id);
            campus_ble_send_ack(frame.transfer_id, CAMPUS_ACK_CRC_FAIL);
            return 0;
        case CAMPUS_ASM_COMPLETE:
        default:
            break;
    }

    handle_complete(frame.type, frame.transfer_id);
    return 0;
}

// TX 特征。仓库里踩过的坑：notify-only 的特征也必须有 access_cb，
// 否则 ble_gatts_chr_is_sane() 不通过，整个 service 注册失败并报一个很容易被
// 误读成内存不足的 rc=3。顺便让它可读：读出两个字节就知道对端连的是不是这个协议。
static int tx_access(uint16_t conn_handle, uint16_t attr_handle,
                     struct ble_gatt_access_ctxt *ctxt, void *arg) {
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t ident[2] = {CAMPUS_FRAME_MAGIC, CAMPUS_FRAME_VERSION};
        return os_mbuf_append(ctxt->om, ident, sizeof(ident)) == 0
                ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

// ------------------------------------------------------------------ 广播

static int advertise(void) {
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    // 128 位 UUID 占 18 字节，广播包只有 31 字节，所以名字放扫描响应里。
    fields.uuids128 = &s_svc_uuid.u128;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        return rc;
    }

    struct ble_hs_adv_fields rsp = {0};
    rsp.name = (const uint8_t *)DEVICE_NAME;
    rsp.name_len = strlen(DEVICE_NAME);
    rsp.name_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        return rc;
    }

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;  // 可连接
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    // 显式给广播间隔。留 0 会让控制器用默认值，日志里就是 adv_itvl_min=0/0，
    // 排查「手机扫不到」时没法判断实际节奏。单位 0.625ms：0x30=30ms，0x60=60ms。
    params.itvl_min = 0x0030;
    params.itvl_max = 0x0060;
    return ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
}

static int gap_event(struct ble_gap_event *event, void *arg) {
    (void)arg;

    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status != 0) {
                ESP_LOGW(TAG, "连接失败 (%d)，重新广播", event->connect.status);
                advertise();
                return 0;
            }
            s_conn_handle = event->connect.conn_handle;
            campus_assembler_reset(&s_assembler);
            ESP_LOGI(TAG, "已连接 (handle=%d)，主动发起加密", s_conn_handle);
            {
                // 主动加密：让配对在连上时就发生，而不是等第一次写加密特征时才触发。
                int rc = ble_gap_security_initiate(s_conn_handle);
                if (rc != 0 && rc != BLE_HS_EALREADY) {
                    ESP_LOGW(TAG, "发起加密失败: %d", rc);
                }
            }
            if (s_link_cb != NULL) {
                s_link_cb(true);
            }
            return 0;

        case BLE_GAP_EVENT_DISCONNECT:
            ESP_LOGI(TAG, "已断开 (reason=%d)，重新广播", event->disconnect.reason);
            s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
            campus_assembler_reset(&s_assembler);
            // 配对码只对当次配对有效：断开就撤下，免得屏上留一个已经作废的码，
            // 用户照着输进去只会白费一次。
            if (s_passkey_cb != NULL) {
                s_passkey_cb(0);
            }
            if (s_link_cb != NULL) {
                s_link_cb(false);
            }
            advertise();
            return 0;

        case BLE_GAP_EVENT_ENC_CHANGE:
            ESP_LOGI(TAG, "加密状态变更: status=%d", event->enc_change.status);
            if (event->enc_change.status == 0 && s_passkey_cb != NULL) {
                // 配对成功，配对码立刻作废并撤下屏幕。
                s_passkey_cb(0);
            }
            return 0;

        case BLE_GAP_EVENT_PASSKEY_ACTION: {
            struct ble_sm_io io = {0};
            io.action = event->passkey.params.action;
            if (io.action == BLE_SM_IOACT_DISP) {
                // 配对码由本机生成、显示在屏上，手机侧输入 —— MITM 保护就在这一步。
                //
                // 刻意**不写进日志**：这个码是给屏幕前那个人看的，串口日志一写就等于
                // 把「只能当面看到」的秘密抄送给了任何能读日志的人，MITM 保护也就名存实亡。
                uint32_t passkey = esp_random() % 1000000u;
                io.passkey = passkey;
                ESP_LOGI(TAG, "配对码已显示在屏幕上，请在手机侧输入");
                if (s_passkey_cb != NULL) {
                    s_passkey_cb(passkey);
                }
            }
            int rc = ble_sm_inject_io(event->passkey.conn_handle, &io);
            if (rc != 0) {
                ESP_LOGW(TAG, "注入配对信息失败: %d", rc);
            }
            return 0;
        }

        case BLE_GAP_EVENT_MTU:
            ESP_LOGI(TAG, "MTU 协商为 %d", event->mtu.value);
            return 0;

        case BLE_GAP_EVENT_SUBSCRIBE:
            ESP_LOGI(TAG, "订阅变更: attr=%d notify=%d",
                     event->subscribe.attr_handle, event->subscribe.cur_notify);
            return 0;

        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise();
            return 0;

        case BLE_GAP_EVENT_REPEAT_PAIRING: {
            // 对端存着我们早已删掉的密钥。删掉旧绑定让它重新配对，而不是直接拒绝。
            struct ble_gap_conn_desc desc;
            if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
                ble_store_util_delete_peer(&desc.peer_id_addr);
            }
            return BLE_GAP_REPEAT_PAIRING_RETRY;
        }

        default:
            return 0;
    }
}

// ------------------------------------------------------------------ 生命周期

static void on_reset(int reason) {
    ESP_LOGE(TAG, "NimBLE 复位: %d", reason);
}

static void on_sync(void) {
    // 必须在这里：此刻服务已注册、句柄已分配（见 resolve_tx_handle 的说明）。
    resolve_tx_handle();

    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) {
        rc = ble_hs_id_infer_auto(0, &s_addr_type);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "获取蓝牙地址失败: %d", rc);
        return;
    }
    rc = advertise();
    if (rc != 0) {
        ESP_LOGE(TAG, "开始广播失败: %d", rc);
    } else {
        ESP_LOGI(TAG, "广播中，服务 " UUID_SERVICE);
    }
}

static void host_task(void *arg) {
    (void)arg;
    // 本应用不需要停止 BLE，所以只跑一次 nimble_port_run()，
    // 不像仓库那个 demo 还要管 stop/deinit 与任务回收。
    nimble_port_run();
    vTaskDelete(NULL);
}

static void register_services(void) {
    ble_uuid_from_str(&s_svc_uuid, UUID_SERVICE);
    ble_uuid_from_str(&s_rx_uuid, UUID_RX);
    ble_uuid_from_str(&s_tx_uuid, UUID_TX);

    memset(s_chrs, 0, sizeof(s_chrs));
    s_chrs[0].uuid = &s_rx_uuid.u;
    s_chrs[0].access_cb = rx_access;
    // 要求加密 + 认证：没有绑定就先配对，配不上就写不进来。
    s_chrs[0].flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN;

    s_chrs[1].uuid = &s_tx_uuid.u;
    s_chrs[1].access_cb = tx_access;
    s_chrs[1].flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_READ_AUTHEN;
    // s_chrs[2] 全零即终止项

    memset(s_svcs, 0, sizeof(s_svcs));
    s_svcs[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
    s_svcs[0].uuid = &s_svc_uuid.u;
    s_svcs[0].characteristics = s_chrs;

    int rc = ble_gatts_count_cfg(s_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gatts_count_cfg 失败: %d", rc);
        return;
    }
    rc = ble_gatts_add_svcs(s_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gatts_add_svcs 失败: %d", rc);
        return;
    }
    // 这里**不能**查 TX 句柄：ble_gatts_add_svcs 只是把服务定义入队，
    // 真正的注册与句柄分配发生在 ble_gatts_start()，而它由协议栈在 sync 时调用。
    // 查询放 resolve_tx_handle()，由 sync 回调触发。
}

// TX 特征值句柄在协议栈 sync 之后才存在。
//
// 协议栈的顺序是：ble_gatts_start()（分配句柄）→ ble_hs_sync() → sync 回调。
// 先前把查询写在 add_svcs 之后，拿到的是 BLE_HS_ENOENT，于是 s_tx_val_handle 一直是 0，
// notify_frame 每次都在开头就返回 —— 设备永远发不出 ACK 和 OP，而日志上只有一行
// 「找不到 TX 特征」。这个错误只在真机上暴露得出来。
static void resolve_tx_handle(void) {
    uint16_t val_handle = 0;
    int rc = ble_gatts_find_chr(&s_svc_uuid.u, &s_tx_uuid.u, NULL, &val_handle);
    if (rc != 0) {
        ESP_LOGE(TAG, "找不到 TX 特征: %d（通知将完全不可用）", rc);
        return;
    }
    s_tx_val_handle = val_handle;
    ESP_LOGI(TAG, "TX value handle = %u", (unsigned)val_handle);
}

esp_err_t campus_ble_start(void) {
    if (s_started) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init 失败: %s", esp_err_to_name(err));
        return err;
    }

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_svc_gap_device_name_set(DEVICE_NAME);
    if (rc != 0) {
        ESP_LOGE(TAG, "设置设备名失败: %d", rc);
        return ESP_FAIL;
    }

    register_services();

    // LE Secure Connections + 绑定 + MITM。设备只有屏幕没有键盘，所以 IO 能力是
    // DISP_ONLY：配对码显示在屏上，手机侧输入。
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;

    if (xTaskCreate(host_task, "nimble_host", HOST_TASK_STACK, NULL, 5, NULL) != pdPASS) {
        ESP_LOGE(TAG, "创建 NimBLE host 任务失败");
        return ESP_ERR_NO_MEM;
    }

    s_tx_lock = xSemaphoreCreateMutex();
    if (s_tx_lock == NULL) {
        ESP_LOGE(TAG, "创建发送互斥锁失败");
        return ESP_ERR_NO_MEM;
    }

    s_started = true;
    return ESP_OK;
}

bool campus_ble_connected(void) {
    return s_conn_handle != BLE_HS_CONN_HANDLE_NONE;
}

void campus_ble_set_target(campus_list_t *list) {
    s_target = list;
}

void campus_ble_on_passkey(campus_ble_passkey_cb_t cb) {
    s_passkey_cb = cb;
}

void campus_ble_on_snapshot(campus_ble_snapshot_cb_t cb) {
    s_snapshot_cb = cb;
}

void campus_ble_on_link(campus_ble_link_cb_t cb) {
    s_link_cb = cb;
}
