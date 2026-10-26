// main/campus_model.h —— 待办列表的排序、查找与状态/动作名的编解码。
//
// 同样不依赖 ESP-IDF，可用主机编译器单测。
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "campus_types.h"

// 排序：未完成在前（按截止时间升序，没时间的排最后）→ 待确认 → 已完成 → 通知。
// out 里放的是指向 in 内部元素的指针，不复制数据；返回写入条数。
uint16_t campus_model_sorted(const campus_list_t *in, const campus_todo_t **out, uint16_t capacity);

campus_todo_t *campus_model_find(campus_list_t *list, uint32_t id);

// 状态与动作在协议里是字符串（"active" / "pending" / "done" / "info"），
// 用名字而不是数字，是为了让抓到的包肉眼可读。
const char *campus_status_name(campus_status_t status);
bool campus_status_parse(const char *text, campus_status_t *out);

const char *campus_op_name(campus_op_action_t action);
