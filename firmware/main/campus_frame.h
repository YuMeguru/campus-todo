// main/campus_frame.h —— campus-sync/1 的链路帧（设备侧）。
//
// 与安卓端 BleFrame.java 是同一份协议的两端，字段顺序与字节序必须逐字节一致：
//
//   0   magic          0xA7
//   1   ver            1
//   2   type
//   3   flags          预留
//   4   transfer_id    u16 小端
//   6   index          u16
//   8   total          u16
//   10  payload_len    u16
//   12  payload_crc16  u16 —— 整个 payload 的 CRC，不是本片的
//   14  payload
//
// 不含任何 ESP-IDF 头文件，可用主机编译器直接编译并单测。
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define CAMPUS_FRAME_MAGIC 0xA7
#define CAMPUS_FRAME_VERSION 1
#define CAMPUS_FRAME_HEADER 14

#define CAMPUS_TYPE_HELLO 0x01
#define CAMPUS_TYPE_SNAPSHOT 0x02
#define CAMPUS_TYPE_DELTA 0x03
#define CAMPUS_TYPE_OP 0x04
#define CAMPUS_TYPE_ACK 0x05

#define CAMPUS_ACK_OK 0
#define CAMPUS_ACK_CRC_FAIL 1
#define CAMPUS_ACK_PARSE_FAIL 2

// 64 条待办 × 约 200 字节 ≈ 13KB，留到 16KB。
// 这是重组缓冲的上限，也是 ramsize 预算里最大的一笔静态开销。
#define CAMPUS_MAX_PAYLOAD 16384

typedef struct {
    uint8_t type;
    uint16_t transfer_id;
    uint16_t index;
    uint16_t total;
    uint16_t payload_crc;
    uint16_t payload_len;
    const uint8_t *payload;  // 指向 raw 内部，不复制
} campus_frame_t;

uint16_t campus_crc16(const uint8_t *data, size_t len);

// 解析一片。返回 false 表示这不是本协议的帧（magic / 版本 / 长度不对）——
// 直接忽略即可，不当异常处理。
bool campus_frame_decode(const uint8_t *raw, size_t len, campus_frame_t *out);

// 组一片。返回写入的字节数；容量不够返回 0。
size_t campus_frame_encode(uint8_t type, uint16_t transfer_id, uint16_t index, uint16_t total,
                           uint16_t payload_crc, const uint8_t *payload, uint16_t payload_len,
                           uint8_t *out, size_t out_capacity);

typedef enum {
    CAMPUS_ASM_NEED_MORE = 0,
    CAMPUS_ASM_COMPLETE,
    CAMPUS_ASM_CRC_FAIL,
    CAMPUS_ASM_DUPLICATE,    // 这一片已经收过，忽略即可
    CAMPUS_ASM_OUT_OF_ORDER, // 跳号了，同一次传输里不该发生
    CAMPUS_ASM_TOO_LARGE,    // 超出重组缓冲
} campus_asm_result_t;

// 线性重组：不做乱序缓存。
//
// 这是刻意的简化，前提是手机侧对 RX 特征用的是 Write With Response ——
// ATT 层保证逐片确认且按序到达，所以乱序不会发生，为它维护一张分片表纯属浪费内存。
// 跳号会被明确报成 OUT_OF_ORDER，而不是拼出一段错位的数据。
typedef struct {
    uint16_t transfer_id;
    uint8_t type;
    uint16_t total;
    uint16_t expected_crc;
    uint16_t next_index;
    uint16_t len;
    // 多出来的那一个字节是给解析器补 '\0' 用的（见 campus_snapshot.h 的契约），
    // 载荷本身仍然被限制在 CAMPUS_MAX_PAYLOAD 以内。
    uint8_t data[CAMPUS_MAX_PAYLOAD + 1];
} campus_assembler_t;

void campus_assembler_reset(campus_assembler_t *assembler);

campus_asm_result_t campus_assembler_offer(campus_assembler_t *assembler, const campus_frame_t *frame);
