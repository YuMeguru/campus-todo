// main/campus_main.c —— 校园待办固件入口。
//
// 阶段 C2：中文界面 + 三键操作 + NVS 持久化 + BLE 同步都已接上。
#include <string.h>

#include "bsp_button.h"
#include "bsp_display.h"
#include "bsp_i2c.h"
#include "campus_ble.h"
#include "campus_model.h"
#include "campus_store.h"
#include "campus_types.h"
#include "campus_ui.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "nvs_flash.h"

static const char *TAG = "campus";

// 设备侧的待办列表，约 15KB 静态。BLE 层解析快照时直接写进这里（省掉一份副本）。
static campus_list_t s_list;

#define INPUT_QUEUE_LENGTH 8
static QueueHandle_t s_input_queue;
static volatile bool s_input_ready;

typedef struct {
    bsp_btn_t btn;
    bsp_btn_ev_t event;
} input_event_t;

// ---------------------------------------------------------------- BLE 回调
// 全部运行在 NimBLE host 任务上：这里只改状态、落盘，绝不碰 LVGL。

static bool on_snapshot(const campus_list_t *list, uint16_t parsed, uint16_t skipped) {
    // 先落盘，再让 BLE 层回 ACK：写失败就返回 false，手机会重传，
    // 而不是「设备说收到了、其实没存」这种两边永久不一致的状态。
    bool stored = campus_store_save(list);
    ESP_LOGI(TAG, "快照 %u 条（跳过 %u），落盘%s",
             (unsigned)parsed, (unsigned)skipped, stored ? "成功" : "失败");
    if (stored) {
        campus_ui_mark_dirty();
    }
    return stored;
}

static void on_passkey(uint32_t passkey) {
    campus_ui_set_passkey(passkey);
}

static void on_link(bool connected) {
    campus_ui_set_link(connected);
}

// ---------------------------------------------------------------- 设备操作

static void on_ui_op(uint32_t todo_id, campus_op_action_t action, uint16_t snooze_minutes) {
    campus_todo_t *todo = campus_model_find(&s_list, todo_id);
    if (todo == NULL) {
        ESP_LOGW(TAG, "界面上的操作找不到对应待办 %u", (unsigned)todo_id);
        return;
    }

    switch (action) {
        case CAMPUS_OP_DONE:
            todo->status = CAMPUS_STATUS_DONE;
            break;
        case CAMPUS_OP_UNDONE:
            todo->status = CAMPUS_STATUS_ACTIVE;
            break;
        case CAMPUS_OP_SNOOZE:
            // 设备没有实时时钟，算不出「一小时后」的绝对时间。
            // 这条不改本地字段，由手机算完在下次同步里带回来。
            break;
        default:
            break;
    }

    // 本地先落盘：断连时用户的点击也不能白点。
    campus_store_save(&s_list);

    // 再回传手机。campus_ble_send_op 内部只是入队，可以从本任务调用。
    campus_ble_send_op(todo_id, action, snooze_minutes);

    campus_ui_mark_dirty();
}

// ---------------------------------------------------------------- 输入

static void handle_key(bsp_btn_t btn, bsp_btn_ev_t event) {
    campus_key_t key;
    if (btn == BSP_BTN_OK) {
        if (event == BSP_BTN_CLICK) {
            key = CAMPUS_KEY_OK;
        } else if (event == BSP_BTN_LONG) {
            key = CAMPUS_KEY_OK_LONG;
        } else {
            return;
        }
    } else if (btn == BSP_BTN_UP && event == BSP_BTN_CLICK) {
        key = CAMPUS_KEY_UP;
    } else if (btn == BSP_BTN_DOWN && event == BSP_BTN_CLICK) {
        key = CAMPUS_KEY_DOWN;
    } else {
        return;
    }

    // 只做一次短锁，按键回调本身不阻塞。
    if (!bsp_lvgl_lock(200)) {
        return;
    }
    campus_ui_key(key);
    bsp_lvgl_unlock();
}

static void input_task(void *arg) {
    (void)arg;
    input_event_t input;
    for (;;) {
        if (xQueueReceive(s_input_queue, &input, portMAX_DELAY) == pdTRUE) {
            handle_key(input.btn, input.event);
        }
    }
}

// 运行在 button 组件的共享 esp_timer 任务上：只入队即返回。
static void on_button(bsp_btn_t btn, bsp_btn_ev_t event, void *user) {
    (void)user;
    if (!s_input_ready || s_input_queue == NULL) {
        return;
    }
    const input_event_t input = {.btn = btn, .event = event};
    (void)xQueueSend(s_input_queue, &input, 0);
}

// LVGL 定时器回调：跑在 LVGL 任务里，已持锁。
static void ui_tick(lv_timer_t *timer) {
    (void)timer;
    campus_ui_refresh();
}

void app_main(void) {
    ESP_LOGI(TAG, "campus-todo firmware 启动");

    esp_err_t err = nvs_flash_init();
    if (err != ESP_OK) {
        // 刻意不擦除：待办快照就存在 NVS 里，擦掉等于清空用户数据。
        // 代价只是 BLE 的 PHY 校准数据读不到，首次启动会做一次全量校准、慢一点而已。
        ESP_LOGW(TAG, "NVS 初始化失败 (%s)，继续启动且不擦除用户数据", esp_err_to_name(err));
    }

    bsp_i2c_init();
    if (bsp_display_init() != ESP_OK || bsp_lvgl_init() == NULL) {
        ESP_LOGE(TAG, "显示或 LVGL 初始化失败，应用无法继续");
        return;
    }
    bsp_display_backlight(70);

    // 必须先于任何 load/save：store 内部那把锁要在这里建起来。
    if (campus_store_init() != ESP_OK) {
        ESP_LOGE(TAG, "初始化存储锁失败");
        return;
    }

    // 先把上一次的快照读回来：界面一上来就有内容，不用等手机连上。
    campus_store_load(&s_list);

    if (!bsp_lvgl_lock(1000)) {
        ESP_LOGE(TAG, "LVGL 加锁失败，界面未创建");
        return;
    }
    campus_ui_init(&s_list);
    campus_ui_refresh();
    lv_timer_create(ui_tick, 200, NULL);
    bsp_lvgl_unlock();

    campus_ui_on_op(on_ui_op);

    // ---- 按键 ----
    s_input_queue = xQueueCreate(INPUT_QUEUE_LENGTH, sizeof(input_event_t));
    if (s_input_queue == NULL) {
        ESP_LOGE(TAG, "创建输入队列失败");
        return;
    }
    if (xTaskCreate(input_task, "campus_input", 4096, NULL, 5, NULL) != pdPASS) {
        ESP_LOGE(TAG, "创建输入任务失败");
        vQueueDelete(s_input_queue);
        s_input_queue = NULL;
        return;
    }
    if (bsp_button_init(on_button, NULL) != ESP_OK) {
        // 按键失败不致命：BLE 同步与持久化仍然工作，只是不能在本机操作。
        ESP_LOGE(TAG, "按键初始化失败，界面将无法操作");
    } else {
        s_input_ready = true;
    }

    // ---- BLE ----
    campus_ble_set_target(&s_list);
    campus_ble_on_snapshot(on_snapshot);
    campus_ble_on_passkey(on_passkey);
    campus_ble_on_link(on_link);

    err = campus_ble_start();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "BLE 启动失败: %s", esp_err_to_name(err));
        return;
    }
    ESP_LOGI(TAG, "就绪：等待手机连接");
}
