#include "campus_snapshot.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "campus_frame.h"  // CAMPUS_MAX_PAYLOAD：快照上限就是协议的重组上限
#include "campus_model.h"

#define FIELD_COUNT 8

size_t campus_snapshot_encode(const campus_list_t *list, char *out, size_t capacity) {
    if (out == NULL || capacity == 0) {
        return 0;
    }
    size_t used = 0;

    int n = snprintf(out, capacity, "%s\n", CAMPUS_SNAPSHOT_HEADER);
    if (n < 0 || (size_t)n >= capacity) {
        return 0;
    }
    used = (size_t)n;

    if (list != NULL) {
        for (uint16_t i = 0; i < list->count; i++) {
            const campus_todo_t *todo = &list->items[i];
            n = snprintf(out + used, capacity - used,
                         "T\t%u\t%lld\t%lld\t%s\t%s\t%s\t%s\n",
                         (unsigned)todo->id,
                         (long long)todo->due_at_ms,
                         (long long)todo->snooze_until_ms,
                         campus_status_name(todo->status),
                         todo->source, todo->convo, todo->title);
            if (n < 0 || (size_t)n >= capacity - used) {
                return 0;  // 截断了：宁可整体失败，也不要写出一份半截快照
            }
            used += (size_t)n;
        }
    }
    return used;
}

static char *take_line(char **cursor) {
    char *p = *cursor;
    if (p == NULL || *p == '\0') {
        return NULL;
    }
    char *nl = strchr(p, '\n');
    if (nl != NULL) {
        *nl = '\0';
        *cursor = nl + 1;
    } else {
        *cursor = p + strlen(p);
    }
    return p;
}

// 把 line 按 '\t' 切成最多 FIELD_COUNT 段，返回实际段数。
static int split_fields(char *line, char *fields[FIELD_COUNT]) {
    int count = 0;
    char *at = line;
    while (count < FIELD_COUNT) {
        fields[count++] = at;
        char *tab = strchr(at, '\t');
        if (tab == NULL) {
            break;
        }
        *tab = '\0';
        at = tab + 1;
    }
    return count;
}

static void copy_field(char *dst, size_t cap, const char *src) {
    if (cap == 0) {
        return;
    }
    size_t i = 0;
    if (src != NULL) {
        for (; i + 1 < cap && src[i] != '\0'; i++) {
            dst[i] = src[i];
        }
    }
    dst[i] = '\0';
}

campus_snapshot_result_t campus_snapshot_parse(char *text, size_t len, campus_list_t *out) {
    campus_snapshot_result_t result = {0, 0, NULL};

    if (out == NULL) {
        result.error = "no output list";
        return result;
    }
    out->count = 0;

    if (text == NULL || len == 0) {
        result.error = "empty snapshot";
        return result;
    }
    if (len > CAMPUS_MAX_PAYLOAD) {
        // 超过重组上限的快照不可能来自本协议，直接拒绝而不是截断解析。
        result.error = "snapshot too large";
        return result;
    }

    char *cursor = text;
    char *line = take_line(&cursor);
    while (line != NULL && line[0] == '\0') {
        line = take_line(&cursor);  // 跳过前导空行
    }
    if (line == NULL || strcmp(line, CAMPUS_SNAPSHOT_HEADER) != 0) {
        result.error = "missing " CAMPUS_SNAPSHOT_HEADER " header";
        return result;
    }

    while ((line = take_line(&cursor)) != NULL) {
        if (line[0] == '\0') {
            continue;
        }
        char *fields[FIELD_COUNT];
        int count = split_fields(line, fields);
        if (count < FIELD_COUNT || strcmp(fields[0], "T") != 0) {
            result.skipped++;
            continue;
        }
        if (out->count >= CAMPUS_MAX_TODOS) {
            result.skipped++;
            continue;
        }

        char *end = NULL;
        unsigned long long id = strtoull(fields[1], &end, 10);
        if (end == fields[1] || id > 0xFFFFFFFFULL) {
            result.skipped++;  // 超出 u32 的 id 直接丢，不要静默截断成另一个待办
            continue;
        }
        long long due = strtoll(fields[2], &end, 10);
        if (end == fields[2]) {
            result.skipped++;
            continue;
        }
        long long snooze = strtoll(fields[3], &end, 10);
        if (end == fields[3]) {
            result.skipped++;
            continue;
        }

        campus_status_t status;
        if (!campus_status_parse(fields[4], &status)) {
            result.skipped++;
            continue;
        }

        campus_todo_t *todo = &out->items[out->count];
        memset(todo, 0, sizeof(*todo));
        todo->id = (uint32_t)id;
        todo->due_at_ms = (int64_t)due;
        todo->snooze_until_ms = (int64_t)snooze;
        todo->status = status;
        copy_field(todo->source, sizeof(todo->source), fields[5]);
        copy_field(todo->convo, sizeof(todo->convo), fields[6]);
        copy_field(todo->title, sizeof(todo->title), fields[7]);

        out->count++;
        result.parsed++;
    }

    // 注意：解析成功但一条都没有（手机上确实没有未完成待办）是合法结果，
    // error 保持 NULL，由调用方按 count == 0 处理。
    return result;
}
