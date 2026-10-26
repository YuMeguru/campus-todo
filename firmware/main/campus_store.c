#include "campus_store.h"

#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs.h"
#include "nvs_flash.h"

#include "campus_frame.h"
#include "campus_snapshot.h"

static const char *TAG = "campus_store";

#define NVS_NAMESPACE "campus"
#define NVS_KEY "snapshot"

// 序列化缓冲，比协议上限略大。约 16KB 静态 —— 这是本工程第三块大缓冲
// （另两块是 BLE 的 16KB 重组缓冲和约 15KB 的待办列表）。
static char s_buffer[CAMPUS_MAX_PAYLOAD + 64];
static SemaphoreHandle_t s_lock;

esp_err_t campus_store_init(void) {
    if (s_lock != NULL) {
        return ESP_OK;
    }
    s_lock = xSemaphoreCreateMutex();
    return s_lock != NULL ? ESP_OK : ESP_ERR_NO_MEM;
}

bool campus_store_load(campus_list_t *list) {
    if (list == NULL || s_lock == NULL) {
        return false;
    }

    xSemaphoreTake(s_lock, portMAX_DELAY);

    nvs_handle_t handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle);
    if (err != ESP_OK) {
        xSemaphoreGive(s_lock);
        ESP_LOGI(TAG, "没有已保存的快照 (%s)", esp_err_to_name(err));
        return false;
    }

    size_t length = sizeof(s_buffer) - 1;
    err = nvs_get_blob(handle, NVS_KEY, s_buffer, &length);
    nvs_close(handle);
    if (err != ESP_OK) {
        xSemaphoreGive(s_lock);
        ESP_LOGI(TAG, "没有已保存的快照 (%s)", esp_err_to_name(err));
        return false;
    }

    // 解析器要求 NUL 结尾。
    s_buffer[length] = '\0';

    campus_snapshot_result_t parsed = campus_snapshot_parse(s_buffer, length, list);
    xSemaphoreGive(s_lock);

    if (parsed.error != NULL) {
        // 存档坏了就当没有：把列表清空继续跑，而不是带着半份数据启动。
        ESP_LOGW(TAG, "已保存的快照解析失败: %s", parsed.error);
        list->count = 0;
        return false;
    }

    ESP_LOGI(TAG, "从 NVS 恢复 %u 条待办 (跳过 %u)", parsed.parsed, parsed.skipped);
    return true;
}

bool campus_store_save(const campus_list_t *list) {
    if (s_lock == NULL) {
        ESP_LOGE(TAG, "campus_store_init() 还没调用");
        return false;
    }

    xSemaphoreTake(s_lock, portMAX_DELAY);

    size_t length = campus_snapshot_encode(list, s_buffer, sizeof(s_buffer));
    if (length == 0) {
        xSemaphoreGive(s_lock);
        ESP_LOGE(TAG, "序列化失败（缓冲不够？%u 条待办）", list == NULL ? 0 : (unsigned)list->count);
        return false;
    }

    nvs_handle_t handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle);
    if (err == ESP_OK) {
        err = nvs_set_blob(handle, NVS_KEY, s_buffer, length);
        if (err == ESP_OK) {
            // 必须 commit：只 set 不提交，掉电就没了 —— 而这里正是用来扛掉电的。
            err = nvs_commit(handle);
        }
        nvs_close(handle);
    }
    xSemaphoreGive(s_lock);

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "写入 NVS 失败: %s", esp_err_to_name(err));
        return false;
    }
    ESP_LOGI(TAG, "已保存 %u 条待办 (%u 字节)",
             list == NULL ? 0 : (unsigned)list->count, (unsigned)length);
    return true;
}
