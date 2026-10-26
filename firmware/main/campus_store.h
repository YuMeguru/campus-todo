// main/campus_store.h —— 待办快照在 NVS 里的持久化。
//
// 存的格式就是协议里那份文本（campus_snapshot_encode），所以：
//   - 不需要第二套格式，读写共用一端的编解码；
//   - 从 NVS 恢复走的是和 BLE 收到快照完全相同的解析路径，两条路不会各错各的。
#pragma once

#include <stdbool.h>

#include "campus_types.h"
#include "esp_err.h"

// 创建内部互斥锁，必须在任何 load/save 之前调用一次。
//
// 为什么需要锁：本模块用一个 16KB 静态缓冲做序列化，而 save 会被两个任务调用 ——
// NimBLE host 任务（收到快照）和输入任务（用户按键）。不加锁时两边会同时改写同一个
// 缓冲，写出一份被截断或串行的存档，然后在下次启动时按坏存档丢掉。
esp_err_t campus_store_init(void);

// 读回上一次保存的列表。没有存档或存档坏了都返回 false（调用方按空列表继续）。
bool campus_store_load(campus_list_t *list);

// 把列表写进 NVS 并提交。返回 true 才算真的落盘 ——
// BLE 层的 ACK 就是拿这个返回值决定的，不能含糊。
bool campus_store_save(const campus_list_t *list);
