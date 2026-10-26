// main/campus_snapshot.h —— 解析手机推来的待办快照（campus-sync/1 的文本载荷）。
//
// 与安卓端 TodoSnapshot.java 是同一份格式的两端：
//
//   campus-sync/1
//   T\t<id>\t<dueAt>\t<snoozeUntil>\t<status>\t<source>\t<convo>\t<title>
//
// 手机侧已经把字段里的制表符与换行替换成空格了，所以这里可以放心按分隔符切。
#pragma once

#include <stddef.h>

#include "campus_types.h"

#define CAMPUS_SNAPSHOT_HEADER "campus-sync/1"

typedef struct {
    uint16_t parsed;
    uint16_t skipped;
    const char *error;  // NULL = 成功
} campus_snapshot_result_t;

// 就地解析：会把缓冲里的 '\n' 与 '\t' 改写成 '\0'。
//
// 契约（两条都必须满足，否则是调用方越界）：
//   1. text 必须可写；
//   2. text 必须以 '\0' 结尾，且末尾那一个字节不算在 len 里
//      —— BLE 层用的重组缓冲是 CAMPUS_MAX_PAYLOAD + 1 字节，正好留出这一位。
campus_snapshot_result_t campus_snapshot_parse(char *text, size_t len, campus_list_t *out);

// 反向：把列表序列化成同一份文本格式（NVS 落盘用，也让两端能对字节）。
//
// 输出不带结尾 '\0'（返回的是有效字节数），因为 BLE 那边收的就是裸字节。
// 容量不够返回 0，此时 out 的内容不可用。
size_t campus_snapshot_encode(const campus_list_t *list, char *out, size_t capacity);
