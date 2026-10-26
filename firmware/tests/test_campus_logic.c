// tests/test_campus_logic.c —— 固件纯逻辑的主机测试。
//
// 覆盖三块：链路帧（CRC / 编解码 / 重组）、快照解析、列表排序。
// 只需要一个 C 编译器，不需要 ESP-IDF、LVGL 或设备。
#include <stdio.h>
#include <string.h>

#include "campus_frame.h"
#include "campus_model.h"
#include "campus_snapshot.h"

static int failures;

#define CHECK(cond)                                                             \
    do {                                                                        \
        if (!(cond)) {                                                          \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);               \
            failures++;                                                         \
        }                                                                       \
    } while (0)

#define CHECK_EQ_U(actual, expected)                                            \
    do {                                                                        \
        unsigned long a_ = (unsigned long)(actual);                             \
        unsigned long e_ = (unsigned long)(expected);                           \
        if (a_ != e_) {                                                         \
            printf("FAIL %s:%d: got %lu want %lu\n", __FILE__, __LINE__, a_, e_); \
            failures++;                                                         \
        }                                                                       \
    } while (0)

#define CHECK_EQ_S(actual, expected)                                            \
    do {                                                                        \
        const char *a_ = (actual);                                              \
        const char *e_ = (expected);                                            \
        if (strcmp(a_, e_) != 0) {                                              \
            printf("FAIL %s:%d: got \"%s\" want \"%s\"\n", __FILE__, __LINE__, a_, e_); \
            failures++;                                                         \
        }                                                                       \
    } while (0)

// ------------------------------------------------------------------ 链路帧

static void test_crc_matches_standard_check_value(void) {
    // CRC-16/CCITT-FALSE 的标准校验值。多项式写错这里立刻红。
    CHECK_EQ_U(campus_crc16((const uint8_t *)"123456789", 9), 0x29B1);
}

static void test_frame_round_trip(void) {
    const char *payload = "hello";
    uint8_t raw[64];
    uint16_t crc = campus_crc16((const uint8_t *)payload, 5);

    size_t n = campus_frame_encode(CAMPUS_TYPE_SNAPSHOT, 7, 2, 5, crc,
                                   (const uint8_t *)payload, 5, raw, sizeof(raw));
    CHECK_EQ_U(n, CAMPUS_FRAME_HEADER + 5);

    campus_frame_t frame;
    CHECK(campus_frame_decode(raw, n, &frame));
    CHECK_EQ_U(frame.type, CAMPUS_TYPE_SNAPSHOT);
    CHECK_EQ_U(frame.transfer_id, 7);
    CHECK_EQ_U(frame.index, 2);
    CHECK_EQ_U(frame.total, 5);
    CHECK_EQ_U(frame.payload_crc, crc);
    CHECK_EQ_U(frame.payload_len, 5);
    CHECK(memcmp(frame.payload, payload, 5) == 0);
}

static void test_frame_rejects_foreign_and_truncated(void) {
    uint8_t raw[64];
    uint8_t bad[64];
    campus_frame_t frame;

    size_t n = campus_frame_encode(CAMPUS_TYPE_HELLO, 1, 0, 1, 0,
                                   (const uint8_t *)"abc", 3, raw, sizeof(raw));

    CHECK(!campus_frame_decode(NULL, 10, &frame));
    CHECK(!campus_frame_decode(raw, CAMPUS_FRAME_HEADER - 1, &frame));

    memcpy(bad, raw, n);
    bad[0] = 0x00;
    CHECK(!campus_frame_decode(bad, n, &frame));

    memcpy(bad, raw, n);
    bad[1] = 9;
    CHECK(!campus_frame_decode(bad, n, &frame));

    // 头里声明 3 字节载荷，只给了 1 字节
    CHECK(!campus_frame_decode(raw, CAMPUS_FRAME_HEADER + 1, &frame));

    // 容量不足返回 0，而不是写越界
    CHECK_EQ_U(campus_frame_encode(CAMPUS_TYPE_HELLO, 1, 0, 1, 0,
                                   (const uint8_t *)"abc", 3, raw, 4), 0);
}

static campus_asm_result_t feed_chunk(campus_assembler_t *assembler, const uint8_t *payload,
                                      size_t payload_len, uint16_t transfer_id, uint16_t index,
                                      uint16_t total, uint16_t crc, uint16_t chunk) {
    uint8_t raw[CAMPUS_FRAME_HEADER + 128];
    size_t offset = (size_t)index * chunk;
    if (offset >= payload_len) {
        return CAMPUS_ASM_OUT_OF_ORDER;
    }
    size_t remaining = payload_len - offset;
    uint16_t len = (uint16_t)(remaining < chunk ? remaining : chunk);

    size_t n = campus_frame_encode(CAMPUS_TYPE_SNAPSHOT, transfer_id, index, total, crc,
                                   payload + offset, len, raw, sizeof(raw));
    campus_frame_t frame;
    if (!campus_frame_decode(raw, n, &frame)) {
        return CAMPUS_ASM_OUT_OF_ORDER;
    }
    return campus_assembler_offer(assembler, &frame);
}

static void test_assembler_happy_path(void) {
    static campus_assembler_t assembler;

    uint8_t payload[500];
    for (size_t i = 0; i < sizeof(payload); i++) {
        payload[i] = (uint8_t)(i * 7u);
    }
    uint16_t crc = campus_crc16(payload, sizeof(payload));
    const uint16_t chunk = 64;
    uint16_t total = (uint16_t)((sizeof(payload) + chunk - 1) / chunk);

    campus_assembler_reset(&assembler);
    campus_asm_result_t result = CAMPUS_ASM_NEED_MORE;
    for (uint16_t i = 0; i < total; i++) {
        result = feed_chunk(&assembler, payload, sizeof(payload), 9, i, total, crc, chunk);
        if ((uint16_t)(i + 1) < total) {
            CHECK_EQ_U(result, CAMPUS_ASM_NEED_MORE);
        }
    }
    CHECK_EQ_U(result, CAMPUS_ASM_COMPLETE);
    CHECK_EQ_U(assembler.len, sizeof(payload));
    CHECK(memcmp(assembler.data, payload, sizeof(payload)) == 0);
}

static void test_assembler_reports_duplicate_and_gap(void) {
    static campus_assembler_t assembler;

    uint8_t payload[200];
    memset(payload, 0x5A, sizeof(payload));
    uint16_t crc = campus_crc16(payload, sizeof(payload));
    uint16_t total = 4;

    campus_assembler_reset(&assembler);
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 3, 0, total, crc, 50),
               CAMPUS_ASM_NEED_MORE);
    // 重传同一片：不该当成新数据，也不该报错
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 3, 0, total, crc, 50),
               CAMPUS_ASM_DUPLICATE);
    CHECK_EQ_U(assembler.next_index, 1);
    // 跳号：明确报出来，而不是拼出错位的数据
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 3, 2, total, crc, 50),
               CAMPUS_ASM_OUT_OF_ORDER);
}

static void test_assembler_crc_fail_is_explicit(void) {
    static campus_assembler_t assembler;

    uint8_t payload[100];
    memset(payload, 0x11, sizeof(payload));
    uint16_t crc = campus_crc16(payload, sizeof(payload));
    uint16_t total = 2;

    campus_assembler_reset(&assembler);
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 5, 0, total, crc, 50),
               CAMPUS_ASM_NEED_MORE);

    // 第二片被改坏。注意不能靠「第二片换个 crc」来触发失败：expected_crc 是在
    // 传输开始（第一片）时锁定的，之后各片自带的 crc 不再参与判断 —— 这正是它该有的语义，
    // 否则中途一片胡说八道就能把整包判成坏包。所以要测的是真的数据坏了。
    uint8_t corrupted[100];
    memcpy(corrupted, payload, sizeof(corrupted));
    corrupted[60] ^= 0xFF;
    CHECK_EQ_U(feed_chunk(&assembler, corrupted, sizeof(corrupted), 5, 1, total, crc, 50),
               CAMPUS_ASM_CRC_FAIL);
}

static void test_assembler_new_transfer_resets(void) {
    static campus_assembler_t assembler;

    uint8_t payload[100];
    memset(payload, 0x22, sizeof(payload));
    uint16_t crc = campus_crc16(payload, sizeof(payload));

    campus_assembler_reset(&assembler);
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 1, 0, 2, crc, 50),
               CAMPUS_ASM_NEED_MORE);
    // 换了 transfer_id：旧的两片作废，重新开始，不应该因为 index 回退而报 DUPLICATE
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 2, 0, 2, crc, 50),
               CAMPUS_ASM_NEED_MORE);
    CHECK_EQ_U(assembler.next_index, 1);
    CHECK_EQ_U(feed_chunk(&assembler, payload, sizeof(payload), 2, 1, 2, crc, 50),
               CAMPUS_ASM_COMPLETE);
}

// -------------------------------------------------------------- 快照解析

static void test_snapshot_parses_all_fields(void) {
    static char buffer[512];
    static campus_list_t list;

    const char *wire =
            "campus-sync/1\n"
            "T\t42\t1760000000000\t0\tactive\t微信\t26造价2班班级群\t提交数据结构实验报告\n"
            "T\t43\t0\t1760000500000\tpending\t钉钉\t辅导员\t开班会\n";
    strcpy(buffer, wire);

    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(wire), &list);
    CHECK(result.error == NULL);
    CHECK_EQ_U(result.parsed, 2);
    CHECK_EQ_U(result.skipped, 0);
    CHECK_EQ_U(list.count, 2);

    CHECK_EQ_U(list.items[0].id, 42);
    CHECK_EQ_U(list.items[0].due_at_ms, 1760000000000LL);
    CHECK_EQ_U(list.items[0].status, CAMPUS_STATUS_ACTIVE);
    CHECK_EQ_S(list.items[0].source, "微信");
    CHECK_EQ_S(list.items[0].convo, "26造价2班班级群");
    CHECK_EQ_S(list.items[0].title, "提交数据结构实验报告");

    CHECK_EQ_U(list.items[1].status, CAMPUS_STATUS_PENDING);
    CHECK_EQ_U(list.items[1].snooze_until_ms, 1760000500000LL);
    CHECK_EQ_U(list.items[1].due_at_ms, 0);
}

static void test_snapshot_requires_header(void) {
    static char buffer[128];
    static campus_list_t list;

    strcpy(buffer, "T\t1\t0\t0\tactive\t微信\t群\t标题\n");
    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(buffer), &list);
    CHECK(result.error != NULL);
    CHECK_EQ_U(list.count, 0);
}

static void test_snapshot_counts_malformed_lines(void) {
    static char buffer[512];
    static campus_list_t list;

    const char *wire =
            "campus-sync/1\n"
            "T\t1\t0\t0\tactive\t微信\t群\t好的\n"
            "T\t不是数字\t0\t0\tactive\t微信\t群\t坏的\n"
            "乱七八糟\n"
            "T\t2\t0\t0\tstatus不认识\t微信\t群\t也坏\n"
            "T\t3\t0\t0\tdone\t钉钉\t群\t也好\n";
    strcpy(buffer, wire);

    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(wire), &list);
    CHECK(result.error == NULL);
    CHECK_EQ_U(result.parsed, 2);
    CHECK_EQ_U(result.skipped, 3);
    CHECK_EQ_U(list.count, 2);
    CHECK_EQ_U(list.items[1].id, 3);
    CHECK_EQ_U(list.items[1].status, CAMPUS_STATUS_DONE);
}

static void test_snapshot_rejects_ids_beyond_u32(void) {
    static char buffer[256];
    static campus_list_t list;

    const char *wire =
            "campus-sync/1\n"
            "T\t4294967296\t0\t0\tactive\t微信\t群\t超出u32\n"
            "T\t7\t0\t0\tactive\t微信\t群\t正常\n";
    strcpy(buffer, wire);

    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(wire), &list);
    CHECK(result.error == NULL);
    // 超出 u32 的 id 必须丢掉并计数 —— 截断成另一个 id 会去改到别的待办
    CHECK_EQ_U(result.skipped, 1);
    CHECK_EQ_U(result.parsed, 1);
    CHECK_EQ_U(list.items[0].id, 7);
}

static void test_snapshot_empty_is_valid(void) {
    static char buffer[64];
    static campus_list_t list;

    const char *wire = "campus-sync/1\n";
    strcpy(buffer, wire);

    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(wire), &list);
    CHECK(result.error == NULL);
    CHECK_EQ_U(result.parsed, 0);
    CHECK_EQ_U(list.count, 0);
}

static void test_snapshot_empty_field_survives(void) {
    static char buffer[256];
    static campus_list_t list;

    // 标题为空是合法的（手机侧就是这么发的）；不能因为"字段不够"把它丢掉
    const char *wire = "campus-sync/1\nT\t9\t0\t0\tactive\t\t\t\n";
    strcpy(buffer, wire);

    campus_snapshot_result_t result = campus_snapshot_parse(buffer, strlen(wire), &list);
    CHECK(result.error == NULL);
    CHECK_EQ_U(result.parsed, 1);
    CHECK_EQ_S(list.items[0].title, "");
}

// ------------------------------------------------------------------ 排序

static void test_sorted_puts_open_items_first_by_due_date(void) {
    static campus_list_t list;
    const campus_todo_t *sorted[CAMPUS_MAX_TODOS];

    memset(&list, 0, sizeof(list));
    list.count = 4;
    list.items[0].id = 1;
    list.items[0].status = CAMPUS_STATUS_DONE;
    list.items[0].due_at_ms = 100;
    list.items[1].id = 2;
    list.items[1].status = CAMPUS_STATUS_ACTIVE;
    list.items[1].due_at_ms = 300;
    list.items[2].id = 3;
    list.items[2].status = CAMPUS_STATUS_ACTIVE;
    list.items[2].due_at_ms = 0;  // 没时间：排在同组最后，不能当成 1970 排最前
    list.items[3].id = 4;
    list.items[3].status = CAMPUS_STATUS_PENDING;
    list.items[3].due_at_ms = 200;

    uint16_t count = campus_model_sorted(&list, sorted, CAMPUS_MAX_TODOS);
    CHECK_EQ_U(count, 4);
    CHECK_EQ_U(sorted[0]->id, 2);  // 有时间的 active 最前
    CHECK_EQ_U(sorted[1]->id, 3);  // 没时间的 active 次之
    CHECK_EQ_U(sorted[2]->id, 4);  // 待确认
    CHECK_EQ_U(sorted[3]->id, 1);  // 已完成最后
}

static void test_find_and_status_names(void) {
    static campus_list_t list;
    memset(&list, 0, sizeof(list));
    list.count = 2;
    list.items[0].id = 10;
    list.items[1].id = 20;

    CHECK(campus_model_find(&list, 20) == &list.items[1]);
    CHECK(campus_model_find(&list, 99) == NULL);
    CHECK(campus_model_find(NULL, 1) == NULL);

    campus_status_t status;
    CHECK(campus_status_parse("pending", &status));
    CHECK_EQ_U(status, CAMPUS_STATUS_PENDING);
    CHECK(!campus_status_parse("pending ", &status));
    CHECK(!campus_status_parse("nonsense", &status));
    CHECK_EQ_S(campus_status_name(CAMPUS_STATUS_ACTIVE), "active");
    CHECK_EQ_S(campus_status_name(CAMPUS_STATUS_INFO), "info");
    CHECK_EQ_S(campus_op_name(CAMPUS_OP_SNOOZE), "snooze");
}

// --------------------------------------------------------- 两端共同的固定向量
//
// 安卓端与固件端各自有单测，但两边从来没真正对过话：字节序或 CRC 只要有一边写错，
// 各自的测试都可能照样通过，直到接上硬件才发现。下面这两串是两端唯一的共同锚点 ——
// 安卓端 BleFrameTest.goldenFrameVector / TodoSnapshotTest.goldenSnapshotBytes
// 里硬编码的是同一串字节。

static void test_golden_frame_vector(void) {
    static const uint8_t EXPECTED[] = {
        0xA7, 0x01, 0x02, 0x00, 0x34, 0x12, 0x00, 0x00, 0x01, 0x00,
        0x05, 0x00, 0x6E, 0xD2, 0x68, 0x65, 0x6C, 0x6C, 0x6F,
    };
    const char *payload = "hello";
    uint16_t crc = campus_crc16((const uint8_t *)payload, 5);
    CHECK_EQ_U(crc, 0xD26E);

    uint8_t frame[CAMPUS_FRAME_HEADER + 16];
    size_t n = campus_frame_encode(CAMPUS_TYPE_SNAPSHOT, 0x1234, 0, 1, crc,
                                   (const uint8_t *)payload, 5, frame, sizeof(frame));
    CHECK_EQ_U(n, sizeof(EXPECTED));
    CHECK(n == sizeof(EXPECTED) && memcmp(frame, EXPECTED, sizeof(EXPECTED)) == 0);
}

static void test_golden_snapshot_parses(void) {
    static char buffer[128];
    static campus_list_t list;
    static const char WIRE[] =
            "campus-sync/1\n"
            "T\t42\t1760000000000\t0\tactive\t微信\t班级群\t作业\n";

    // 长度与 CRC 都要和安卓端算出来的一致，UTF-8 多字节字符不能被弄坏。
    CHECK_EQ_U(strlen(WIRE), 66);
    CHECK_EQ_U(campus_crc16((const uint8_t *)WIRE, 66), 0x3438);

    memcpy(buffer, WIRE, sizeof(WIRE));
    campus_snapshot_result_t result = campus_snapshot_parse(buffer, 66, &list);
    CHECK(result.error == NULL);
    CHECK_EQ_U(result.parsed, 1);
    CHECK_EQ_U(list.items[0].id, 42);
    CHECK_EQ_U(list.items[0].due_at_ms, 1760000000000LL);
    CHECK_EQ_S(campus_status_name(list.items[0].status), "active");
    CHECK_EQ_S(list.items[0].source, "微信");
    CHECK_EQ_S(list.items[0].convo, "班级群");
    CHECK_EQ_S(list.items[0].title, "作业");
}

static void test_encode_matches_android_encoder(void) {
    // 安卓端 TodoSnapshotTest.goldenSnapshotBytes 产出的是同一串 66 字节。
    // 固件这边只用来落盘，但格式必须和手机一模一样，否则恢复出来的东西会走形。
    static char out[256];
    static campus_list_t list;

    memset(&list, 0, sizeof(list));
    list.count = 1;
    list.items[0].id = 42;
    list.items[0].due_at_ms = 1760000000000LL;
    list.items[0].snooze_until_ms = 0;
    list.items[0].status = CAMPUS_STATUS_ACTIVE;
    strcpy(list.items[0].source, "微信");
    strcpy(list.items[0].convo, "班级群");
    strcpy(list.items[0].title, "作业");

    size_t n = campus_snapshot_encode(&list, out, sizeof(out));
    CHECK_EQ_U(n, 66);
    CHECK_EQ_U(campus_crc16((const uint8_t *)out, n), 0x3438);
    CHECK(n == 66 && memcmp(out,
                            "campus-sync/1\nT\t42\t1760000000000\t0\tactive\t微信\t班级群\t作业\n",
                            66) == 0);
}

static void test_encode_parse_round_trip(void) {
    static char out[CAMPUS_MAX_PAYLOAD];
    static char copy[CAMPUS_MAX_PAYLOAD];
    static campus_list_t source;
    static campus_list_t back;

    memset(&source, 0, sizeof(source));
    source.count = 3;

    source.items[0].id = 1;
    source.items[0].status = CAMPUS_STATUS_ACTIVE;
    source.items[0].due_at_ms = 1760000000000LL;
    strcpy(source.items[0].source, "微信");
    strcpy(source.items[0].convo, "26造价2班班级群");
    strcpy(source.items[0].title, "提交数据结构实验报告");

    // 空会话名、没有截止时间的待确认条目
    source.items[1].id = 2;
    source.items[1].status = CAMPUS_STATUS_PENDING;
    source.items[1].due_at_ms = 0;
    strcpy(source.items[1].source, "钉钉");
    strcpy(source.items[1].title, "开班会");

    // 通知类，带稍后时间
    source.items[2].id = 3;
    source.items[2].status = CAMPUS_STATUS_INFO;
    source.items[2].snooze_until_ms = 1760000500000LL;
    strcpy(source.items[2].convo, "学习通");
    strcpy(source.items[2].title, "本周晚自习教室安排：至勤楼311");

    size_t n = campus_snapshot_encode(&source, out, sizeof(out));
    CHECK(n > 0);

    memcpy(copy, out, n);
    copy[n] = '\0';
    campus_snapshot_result_t result = campus_snapshot_parse(copy, n, &back);
    CHECK(result.error == NULL);
    CHECK_EQ_U(back.count, 3);
    CHECK_EQ_U(result.skipped, 0);

    for (uint16_t i = 0; i < 3; i++) {
        CHECK_EQ_U(back.items[i].id, source.items[i].id);
        CHECK_EQ_U(back.items[i].due_at_ms, source.items[i].due_at_ms);
        CHECK_EQ_U(back.items[i].snooze_until_ms, source.items[i].snooze_until_ms);
        CHECK_EQ_U(back.items[i].status, source.items[i].status);
        CHECK_EQ_S(back.items[i].source, source.items[i].source);
        CHECK_EQ_S(back.items[i].convo, source.items[i].convo);
        CHECK_EQ_S(back.items[i].title, source.items[i].title);
    }
}

static void test_encode_fails_loudly_when_buffer_is_small(void) {
    // 宁可整体失败，也不要写出一份半截快照 —— 那会被当成合法存档读回来。
    static char tiny[24];
    static campus_list_t list;

    memset(&list, 0, sizeof(list));
    list.count = 2;
    list.items[0].id = 1;
    strcpy(list.items[0].title, "第一条足够长的标题");
    list.items[1].id = 2;
    strcpy(list.items[1].title, "第二条足够长的标题");

    CHECK_EQ_U(campus_snapshot_encode(&list, tiny, sizeof(tiny)), 0);
}

int main(void) {
    test_crc_matches_standard_check_value();
    test_frame_round_trip();
    test_frame_rejects_foreign_and_truncated();
    test_assembler_happy_path();
    test_assembler_reports_duplicate_and_gap();
    test_assembler_crc_fail_is_explicit();
    test_assembler_new_transfer_resets();
    test_snapshot_parses_all_fields();
    test_snapshot_requires_header();
    test_snapshot_counts_malformed_lines();
    test_snapshot_rejects_ids_beyond_u32();
    test_snapshot_empty_is_valid();
    test_snapshot_empty_field_survives();
    test_sorted_puts_open_items_first_by_due_date();
    test_find_and_status_names();
    test_golden_frame_vector();
    test_golden_snapshot_parses();
    test_encode_matches_android_encoder();
    test_encode_parse_round_trip();
    test_encode_fails_loudly_when_buffer_is_small();

    if (failures == 0) {
        printf("host tests: PASS\n");
        return 0;
    }
    printf("%d failure(s)\n", failures);
    return 1;
}
