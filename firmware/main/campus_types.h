// main/campus_types.h —— 设备侧的待办数据模型。
//
// 刻意不含任何 ESP-IDF 头文件：这几个结构体要能被主机编译器编译，
// host 测试才能真正跑到解析与排序逻辑（见 tests/）。
#pragma once

#include <stdbool.h>
#include <stdint.h>

// 上限都是「够用且能静态分配」的量级：设备只有 ~125KB 可用堆（BLE 起来之后），
// 所以全部走静态缓冲，不在运行时 malloc。
#define CAMPUS_MAX_TODOS 64
#define CAMPUS_MAX_TITLE 128
#define CAMPUS_MAX_CONVO 64
#define CAMPUS_MAX_SOURCE 16

typedef enum {
    CAMPUS_STATUS_ACTIVE = 0,
    CAMPUS_STATUS_PENDING,
    CAMPUS_STATUS_DONE,
    CAMPUS_STATUS_INFO,
} campus_status_t;

typedef struct {
    // 手机侧是 SQLite rowid（int64），设备侧按 u32 存：一个学生的待办不可能到 40 亿。
    // 解析时超出 u32 的行会被丢弃并计数，不会静默截断成另一个 id。
    uint32_t id;
    int64_t due_at_ms;        // 0 = 未确定
    int64_t snooze_until_ms;  // 只推迟提醒，不改 due_at
    campus_status_t status;
    char source[CAMPUS_MAX_SOURCE];
    char convo[CAMPUS_MAX_CONVO];
    char title[CAMPUS_MAX_TITLE];
} campus_todo_t;

typedef struct {
    campus_todo_t items[CAMPUS_MAX_TODOS];
    uint16_t count;
} campus_list_t;

// 设备 → 手机的操作
typedef enum {
    CAMPUS_OP_DONE = 0,
    CAMPUS_OP_UNDONE,
    CAMPUS_OP_SNOOZE,
} campus_op_action_t;

typedef struct {
    uint32_t todo_id;
    campus_op_action_t action;
    uint16_t snooze_minutes;
    uint16_t dev_seq;
} campus_op_t;
