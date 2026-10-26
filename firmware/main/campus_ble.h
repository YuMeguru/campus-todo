// main/campus_ble.h —— 设备侧的 GATT server（campus-sync/1）。
//
// 与安卓端 BleClient.java 对接：手机是 central，设备是 peripheral + GATT server。
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "campus_types.h"
#include "esp_err.h"

// 配对码回调。**运行在 NimBLE host 任务上，不要在里面碰 LVGL**——
// 记个变量、置个标志，让 LVGL 定时器去刷新界面。passkey 为 0 表示撤下配对码
// （配对成功或连接断开时都会收到 0）。
typedef void (*campus_ble_passkey_cb_t)(uint32_t passkey);

// 快照收齐并解析完成后的回调，同样跑在 host 任务上。
//
// 返回 true 表示已经**可靠落盘**，此时才回 ACK_OK —— 先落盘再确认，
// 而不是先确认再落盘：否则落盘失败时手机会以为同步成功了，两边状态永久不一致。
// 返回 false 会回 ACK_PARSE_FAIL，手机侧会重传。
//
// 实现里不要调用 LVGL：这里是 host 任务。
typedef bool (*campus_ble_snapshot_cb_t)(const campus_list_t *list, uint16_t parsed, uint16_t skipped);

typedef void (*campus_ble_link_cb_t)(bool connected);

// 解析结果直接写进这块缓冲，省掉一份 15KB 的副本。必须在 start 之前设置。
void campus_ble_set_target(campus_list_t *list);

void campus_ble_on_passkey(campus_ble_passkey_cb_t cb);
void campus_ble_on_snapshot(campus_ble_snapshot_cb_t cb);
void campus_ble_on_link(campus_ble_link_cb_t cb);

// 初始化 NimBLE、注册服务、开始广播。只调一次；本应用不需要停止 BLE。
esp_err_t campus_ble_start(void);

bool campus_ble_connected(void);

// 设备 → 手机
void campus_ble_send_ack(uint16_t transfer_id, uint8_t status);
void campus_ble_send_op(uint32_t todo_id, campus_op_action_t action, uint16_t snooze_minutes);
