// main/campus_ui.h —— 设备端界面（列表 / 详情，三键操作）。
//
// 线程约束：本模块所有函数都必须在 **LVGL 锁内 / LVGL 任务里** 调用
// （campus_ui_refresh 由 LVGL 定时器驱动，campus_ui_key 由输入任务持锁调用）。
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "campus_types.h"

typedef enum {
    CAMPUS_KEY_UP = 0,
    CAMPUS_KEY_DOWN,
    CAMPUS_KEY_OK,
    CAMPUS_KEY_OK_LONG,
} campus_key_t;

// 用户在设备上操作了某条待办。实现里**不要**直接发 BLE 通知：
// 本回调跑在输入任务上，发送交给 BLE 模块自己的发送任务（见 campus_ble.c）。
typedef void (*campus_ui_op_cb_t)(uint32_t todo_id, campus_op_action_t action,
                                  uint16_t snooze_minutes);

void campus_ui_on_op(campus_ui_op_cb_t cb);

// 必须在 LVGL 锁内调用，且要先把 list 准备好。
void campus_ui_init(campus_list_t *list);

// 连接状态变化（由 BLE 回调置位，界面在定时器里读）。任意任务可调。
void campus_ui_set_link(bool connected);

// 要显示在页面上的配对码；**0 表示撤下**（配对成功或断开时由 BLE 层回调置 0）。
// 任意任务可调（只写一个 volatile 变量）。
void campus_ui_set_passkey(uint32_t passkey);

// 待办数量变化时置位，界面在下一帧重置列表。
void campus_ui_mark_dirty(void);

// LVGL 任务里调用。
void campus_ui_refresh(void);

// LVGL 锁内调用。
void campus_ui_key(campus_key_t key);
