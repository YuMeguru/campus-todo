#include "campus_model.h"

#include <string.h>

static int status_rank(campus_status_t status) {
    switch (status) {
        case CAMPUS_STATUS_ACTIVE:
            return 0;
        case CAMPUS_STATUS_PENDING:
            return 1;
        case CAMPUS_STATUS_DONE:
            return 2;
        case CAMPUS_STATUS_INFO:
        default:
            return 3;
    }
}

// true 表示 a 应该排在 b 前面。
static bool before(const campus_todo_t *a, const campus_todo_t *b) {
    int ra = status_rank(a->status);
    int rb = status_rank(b->status);
    if (ra != rb) {
        return ra < rb;
    }
    // 没有截止时间的排在同组最后，而不是被当成 1970 年排到最前。
    bool a_has = a->due_at_ms > 0;
    bool b_has = b->due_at_ms > 0;
    if (a_has != b_has) {
        return a_has;
    }
    if (a_has && a->due_at_ms != b->due_at_ms) {
        return a->due_at_ms < b->due_at_ms;
    }
    return a->id < b->id;  // 同刻的按 id 稳定排序，避免每次刷新顺序抖动
}

uint16_t campus_model_sorted(const campus_list_t *in, const campus_todo_t **out, uint16_t capacity) {
    if (in == NULL || out == NULL || capacity == 0) {
        return 0;
    }
    uint16_t count = in->count < capacity ? in->count : capacity;
    for (uint16_t i = 0; i < count; i++) {
        out[i] = &in->items[i];
    }
    // 列表上限 64 条，插入排序足够，而且没有额外内存。
    for (uint16_t i = 1; i < count; i++) {
        const campus_todo_t *key = out[i];
        int j = (int)i - 1;
        while (j >= 0 && before(key, out[j])) {
            out[j + 1] = out[j];
            j--;
        }
        out[j + 1] = key;
    }
    return count;
}

campus_todo_t *campus_model_find(campus_list_t *list, uint32_t id) {
    if (list == NULL) {
        return NULL;
    }
    for (uint16_t i = 0; i < list->count; i++) {
        if (list->items[i].id == id) {
            return &list->items[i];
        }
    }
    return NULL;
}

const char *campus_status_name(campus_status_t status) {
    switch (status) {
        case CAMPUS_STATUS_ACTIVE:
            return "active";
        case CAMPUS_STATUS_PENDING:
            return "pending";
        case CAMPUS_STATUS_DONE:
            return "done";
        case CAMPUS_STATUS_INFO:
        default:
            return "info";
    }
}

bool campus_status_parse(const char *text, campus_status_t *out) {
    if (text == NULL || out == NULL) {
        return false;
    }
    if (strcmp(text, "active") == 0) {
        *out = CAMPUS_STATUS_ACTIVE;
    } else if (strcmp(text, "pending") == 0) {
        *out = CAMPUS_STATUS_PENDING;
    } else if (strcmp(text, "done") == 0) {
        *out = CAMPUS_STATUS_DONE;
    } else if (strcmp(text, "info") == 0) {
        *out = CAMPUS_STATUS_INFO;
    } else {
        return false;
    }
    return true;
}

const char *campus_op_name(campus_op_action_t action) {
    switch (action) {
        case CAMPUS_OP_DONE:
            return "done";
        case CAMPUS_OP_UNDONE:
            return "undone";
        case CAMPUS_OP_SNOOZE:
            return "snooze";
        default:
            return "?";
    }
}
