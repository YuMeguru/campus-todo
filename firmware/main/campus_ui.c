#include "campus_ui.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

#include "esp_log.h"
#include "lvgl.h"

#include "campus_model.h"

static const char *TAG = "campus_ui";

LV_FONT_DECLARE(campus_font_16);  // ASCII + GB2312 一级汉字（动态标题靠它）
LV_FONT_DECLARE(campus_font_20);  // 只有界面文案，用于标题与页眉

// 配色与手机端保持一致，两块屏看起来是一套东西。
#define COL_BG      0x101215
#define COL_CARD    0x1B1E24
#define COL_SEL     0x2A3550
#define COL_TEXT    0xFFFFFF
#define COL_MUTED   0xA7B0BE
#define COL_ACCENT  0x6EA8FE
#define COL_WARN    0xFFB84D
#define COL_OK      0x6DD58C

#define ROW_COUNT 4
#define ROW_HEIGHT 62
#define LIST_TOP 30

typedef struct {
    lv_obj_t *row;
    lv_obj_t *title;
    lv_obj_t *meta;
} row_view_t;

static campus_list_t *s_list;
static campus_ui_op_cb_t s_op_cb;

static lv_obj_t *s_list_page;
static lv_obj_t *s_header;
static lv_obj_t *s_hint;

// 配对码浮层：独立于列表页与详情页，永远盖在最上层。
static lv_obj_t *s_pair_box;
static lv_obj_t *s_pair_text;
static row_view_t s_rows[ROW_COUNT];

static lv_obj_t *s_detail_page;
static lv_obj_t *s_detail_title;
static lv_obj_t *s_detail_convo;
static lv_obj_t *s_detail_due;
static lv_obj_t *s_detail_source;
static lv_obj_t *s_action_labels[3];
static lv_obj_t *s_action_boxes[3];

static volatile bool s_connected;
static volatile bool s_dirty = true;
static volatile uint32_t s_passkey;

static uint16_t s_cursor;      // 当前选中的待办
static uint16_t s_scroll;      // 列表顶部对应的下标
static uint8_t s_action;       // 详情页当前动作
static bool s_in_detail;

// 排序后的视图（指针数组），不复制数据。
static const campus_todo_t *s_sorted[CAMPUS_MAX_TODOS];
static uint16_t s_sorted_count;

// ------------------------------------------------------------------ 小工具

static lv_obj_t *make_label(lv_obj_t *parent, const lv_font_t *font, uint32_t color) {
    lv_obj_t *label = lv_label_create(parent);
    lv_obj_set_style_text_font(label, font, 0);
    lv_obj_set_style_text_color(label, lv_color_hex(color), 0);
    return label;
}

static const char *status_text(const campus_todo_t *todo) {
    switch (todo->status) {
        case CAMPUS_STATUS_PENDING:
            return "待确认";
        case CAMPUS_STATUS_DONE:
            return "已完成";
        case CAMPUS_STATUS_INFO:
            return "通知";
        default:
            return "进行中";
    }
}

static uint32_t status_color(const campus_todo_t *todo) {
    switch (todo->status) {
        case CAMPUS_STATUS_PENDING:
            return COL_WARN;
        case CAMPUS_STATUS_DONE:
            return COL_MUTED;
        case CAMPUS_STATUS_INFO:
            return COL_MUTED;
        default:
            return COL_ACCENT;
    }
}

// 把毫秒时间戳写成 MM-DD HH:MM。设备没有本地时区，按 UTC 显示就够了 ——
// 刻意不做时区，因为时区数据会白占几十 KB flash，而这块屏只看「什么时候截止」。
static void format_due(int64_t millis, char *out, size_t cap) {
    if (millis <= 0) {
        snprintf(out, cap, "时间待确认");
        return;
    }
    time_t seconds = (time_t)(millis / 1000);
    struct tm tm_utc;
    gmtime_r(&seconds, &tm_utc);
    snprintf(out, cap, "%02d-%02d %02d:%02d",
             tm_utc.tm_mon + 1, tm_utc.tm_mday, tm_utc.tm_hour, tm_utc.tm_min);
}

// ------------------------------------------------------------------ 列表页

static void build_list_page(lv_obj_t *screen) {
    s_list_page = lv_obj_create(screen);
    lv_obj_set_size(s_list_page, 240, 320);
    lv_obj_set_pos(s_list_page, 0, 0);
    lv_obj_set_style_bg_color(s_list_page, lv_color_hex(COL_BG), 0);
    lv_obj_set_style_border_width(s_list_page, 0, 0);
    lv_obj_set_style_pad_all(s_list_page, 0, 0);
    lv_obj_set_style_radius(s_list_page, 0, 0);
    lv_obj_set_scrollable(s_list_page, false);

    s_header = make_label(s_list_page, &campus_font_20, COL_TEXT);
    lv_obj_set_pos(s_header, 8, 4);

    for (int i = 0; i < ROW_COUNT; i++) {
        row_view_t *view = &s_rows[i];
        view->row = lv_obj_create(s_list_page);
        lv_obj_set_size(view->row, 224, ROW_HEIGHT - 6);
        lv_obj_set_pos(view->row, 8, LIST_TOP + i * ROW_HEIGHT);
        lv_obj_set_style_bg_color(view->row, lv_color_hex(COL_CARD), 0);
        lv_obj_set_style_border_width(view->row, 0, 0);
        lv_obj_set_style_radius(view->row, 6, 0);
        lv_obj_set_style_pad_all(view->row, 8, 0);
        lv_obj_set_scrollable(view->row, false);

        view->title = make_label(view->row, &campus_font_16, COL_TEXT);
        lv_obj_set_width(view->title, 200);
        lv_label_set_long_mode(view->title, LV_LABEL_LONG_DOT);
        lv_obj_set_pos(view->title, 0, 0);

        view->meta = make_label(view->row, &campus_font_16, COL_MUTED);
        lv_obj_set_width(view->meta, 200);
        lv_label_set_long_mode(view->meta, LV_LABEL_LONG_DOT);
        lv_obj_set_pos(view->meta, 0, 22);
    }

    s_hint = make_label(s_list_page, &campus_font_16, COL_MUTED);
    lv_obj_set_pos(s_hint, 8, 296);
}

// 重建排序视图并夹住游标。
//
// 必须在**每次使用前**调用，不能只在重绘时调：BLE 收到新快照是原地改写 s_list 的，
// 而 s_sorted 存的是指向它的指针。只在 200ms 的定时器里重建，这中间按下的键就会拿着
// 一个指向旧内容的游标 —— 轻则操作到已经不存在的待办，重则游标越界。
static void rebuild_sorted(void) {
    s_sorted_count = campus_model_sorted(s_list, s_sorted, CAMPUS_MAX_TODOS);

    if (s_sorted_count == 0) {
        s_cursor = 0;
        s_scroll = 0;
        return;
    }
    if (s_cursor >= s_sorted_count) {
        s_cursor = (uint16_t)(s_sorted_count - 1);
    }
    if (s_cursor < s_scroll) {
        s_scroll = s_cursor;
    }
    if (s_cursor >= s_scroll + ROW_COUNT) {
        s_scroll = (uint16_t)(s_cursor - ROW_COUNT + 1);
    }
}

static void refresh_list_page(void) {
    rebuild_sorted();

    if (s_sorted_count == 0) {
        lv_label_set_text(s_header, s_connected ? "已连接" : "等待手机");
    } else {
        lv_label_set_text_fmt(s_header, "%s · %u 条",
                              s_connected ? "已连接" : "等待手机", (unsigned)s_sorted_count);
    }

    for (int i = 0; i < ROW_COUNT; i++) {
        uint16_t index = (uint16_t)(s_scroll + i);
        if (index >= s_sorted_count) {
            lv_obj_set_hidden(s_rows[i].row, true);
            continue;
        }
        lv_obj_set_hidden(s_rows[i].row, false);

        const campus_todo_t *todo = s_sorted[index];
        char due[32];
        format_due(todo->due_at_ms, due, sizeof(due));

        lv_label_set_text(s_rows[i].title, todo->title);
        lv_obj_set_style_text_color(s_rows[i].title,
                                    todo->status == CAMPUS_STATUS_DONE
                                            ? lv_color_hex(COL_MUTED)
                                            : lv_color_hex(COL_TEXT), 0);
        lv_label_set_text_fmt(s_rows[i].meta, "%s · %s · %s",
                              due, status_text(todo),
                              todo->convo[0] != '\0' ? todo->convo : todo->source);

        bool selected = index == s_cursor;
        lv_obj_set_style_bg_color(s_rows[i].row,
                                  lv_color_hex(selected ? COL_SEL : COL_CARD), 0);
        lv_obj_set_style_border_width(s_rows[i].row, selected ? 2 : 0, 0);
        lv_obj_set_style_border_color(s_rows[i].row,
                                      lv_color_hex(status_color(todo)), 0);
    }

    // 提示行只管列表本身的引导。配对码**不在这里**显示 —— 它有自己的浮层，
    // 见 refresh_pair_overlay（这里曾经用「列表为空才显示配对码」把它挡掉了）。
    if (s_sorted_count == 0) {
        lv_label_set_text(s_hint, "还没有待办，等手机同步");
    } else {
        lv_label_set_text(s_hint, "上下选择 · 确定查看");
    }
    lv_obj_set_style_text_color(s_hint, lv_color_hex(COL_MUTED), 0);
}

// ------------------------------------------------------------------ 详情页

static const char *ACTION_TEXT[3] = {"标记完成", "稍后 1 小时", "返回"};

static void build_detail_page(lv_obj_t *screen) {
    s_detail_page = lv_obj_create(screen);
    lv_obj_set_size(s_detail_page, 240, 320);
    lv_obj_set_pos(s_detail_page, 0, 0);
    lv_obj_set_style_bg_color(s_detail_page, lv_color_hex(COL_BG), 0);
    lv_obj_set_style_border_width(s_detail_page, 0, 0);
    lv_obj_set_style_pad_all(s_detail_page, 0, 0);
    lv_obj_set_style_radius(s_detail_page, 0, 0);
    lv_obj_set_scrollable(s_detail_page, false);
    lv_obj_set_hidden(s_detail_page, true);

    s_detail_title = make_label(s_detail_page, &campus_font_16, COL_TEXT);
    lv_obj_set_width(s_detail_title, 224);
    lv_label_set_long_mode(s_detail_title, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(s_detail_title, 8, 10);

    s_detail_convo = make_label(s_detail_page, &campus_font_16, COL_MUTED);
    lv_obj_set_width(s_detail_convo, 224);
    lv_label_set_long_mode(s_detail_convo, LV_LABEL_LONG_DOT);
    lv_obj_set_pos(s_detail_convo, 8, 96);

    s_detail_due = make_label(s_detail_page, &campus_font_16, COL_TEXT);
    lv_obj_set_pos(s_detail_due, 8, 128);

    s_detail_source = make_label(s_detail_page, &campus_font_16, COL_MUTED);
    lv_obj_set_pos(s_detail_source, 8, 158);

    for (int i = 0; i < 3; i++) {
        s_action_boxes[i] = lv_obj_create(s_detail_page);
        lv_obj_set_size(s_action_boxes[i], 224, 34);
        lv_obj_set_pos(s_action_boxes[i], 8, 200 + i * 38);
        lv_obj_set_style_bg_color(s_action_boxes[i], lv_color_hex(COL_CARD), 0);
        lv_obj_set_style_border_width(s_action_boxes[i], 0, 0);
        lv_obj_set_style_radius(s_action_boxes[i], 6, 0);
        lv_obj_set_style_pad_all(s_action_boxes[i], 6, 0);
        lv_obj_set_scrollable(s_action_boxes[i], false);

        s_action_labels[i] = make_label(s_action_boxes[i], &campus_font_16, COL_TEXT);
        lv_obj_set_pos(s_action_labels[i], 0, 0);
        lv_label_set_text(s_action_labels[i], ACTION_TEXT[i]);
    }
}

static const campus_todo_t *current_todo(void) {
    if (s_sorted_count == 0 || s_cursor >= s_sorted_count) {
        return NULL;
    }
    return s_sorted[s_cursor];
}

static void refresh_detail_page(void) {
    const campus_todo_t *todo = current_todo();
    if (todo == NULL) {
        return;
    }
    char due[32];
    format_due(todo->due_at_ms, due, sizeof(due));

    lv_label_set_text(s_detail_title, todo->title);
    lv_label_set_text_fmt(s_detail_convo, "来自 %s",
                          todo->convo[0] != '\0' ? todo->convo : "未知会话");
    lv_label_set_text_fmt(s_detail_due, "截止 %s · %s", due, status_text(todo));
    lv_label_set_text_fmt(s_detail_source, "来源 %s",
                          todo->source[0] != '\0' ? todo->source : "未知");

    // 已完成的条目就不该再显示「标记完成」。
    bool done = todo->status == CAMPUS_STATUS_DONE;
    lv_label_set_text(s_action_labels[0], done ? "标记未完成" : "标记完成");

    for (int i = 0; i < 3; i++) {
        bool selected = i == (int)s_action;
        lv_obj_set_style_bg_color(s_action_boxes[i],
                                  lv_color_hex(selected ? COL_SEL : COL_CARD), 0);
        lv_obj_set_style_border_width(s_action_boxes[i], selected ? 2 : 0, 0);
        lv_obj_set_style_border_color(s_action_boxes[i], lv_color_hex(COL_ACCENT), 0);
    }
}

static void show_detail(bool show) {
    s_in_detail = show;
    if (show) {
        s_action = 0;
        refresh_detail_page();
        lv_obj_set_hidden(s_list_page, true);
        lv_obj_set_hidden(s_detail_page, false);
    } else {
        lv_obj_set_hidden(s_detail_page, true);
        lv_obj_set_hidden(s_list_page, false);
        s_dirty = true;
    }
}

// ------------------------------------------------------------------ 对外接口

void campus_ui_on_op(campus_ui_op_cb_t cb) {
    s_op_cb = cb;
}

void campus_ui_set_link(bool connected) {
    s_connected = connected;
    s_dirty = true;
}

void campus_ui_set_passkey(uint32_t passkey) {
    s_passkey = passkey;
    s_dirty = true;
}

void campus_ui_mark_dirty(void) {
    s_dirty = true;
}

// 配对码浮层。
//
// 必须**无条件**显示：它是完成配对的唯一途径，屏上没别的对方能拿到这个码。
// 早先把它挂在「列表为空」的条件里，于是设备一旦从 NVS 恢复出内容，配对码就永远
// 不出现 —— 表现就是「有内容之后就再也配不了对」。
static void build_pair_overlay(lv_obj_t *screen) {
    s_pair_box = lv_obj_create(screen);
    lv_obj_set_size(s_pair_box, 224, 56);
    lv_obj_align(s_pair_box, LV_ALIGN_TOP_MID, 0, 6);
    lv_obj_set_style_bg_color(s_pair_box, lv_color_hex(COL_WARN), 0);
    lv_obj_set_style_border_width(s_pair_box, 0, 0);
    lv_obj_set_style_radius(s_pair_box, 8, 0);
    lv_obj_set_style_pad_all(s_pair_box, 6, 0);
    lv_obj_set_scrollable(s_pair_box, false);
    lv_obj_set_hidden(s_pair_box, true);

    // 深色字压在琥珀底上：这块屏上对比度最高的组合，配对码要隔着桌子也看得清。
    s_pair_text = make_label(s_pair_box, &campus_font_20, COL_BG);
    lv_obj_center(s_pair_text);
}

static void refresh_pair_overlay(void) {
    uint32_t passkey = s_passkey;
    if (passkey == 0) {
        lv_obj_set_hidden(s_pair_box, true);
        return;
    }
    lv_label_set_text_fmt(s_pair_text, "配对码 %06u", (unsigned)passkey);
    lv_obj_set_hidden(s_pair_box, false);
    lv_obj_move_foreground(s_pair_box);
}

void campus_ui_init(campus_list_t *list) {
    s_list = list;
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(COL_BG), 0);
    build_list_page(screen);
    build_detail_page(screen);
    build_pair_overlay(screen);  // 最后建，天然在最上层
    s_dirty = true;
    ESP_LOGI(TAG, "界面已创建");
}

void campus_ui_refresh(void) {
    // 每帧都刷，不走 s_dirty：配对码是会过期的信息，出现和消失都不能被延迟。
    refresh_pair_overlay();

    if (s_dirty) {
        s_dirty = false;
        if (s_in_detail) {
            refresh_detail_page();
        } else {
            refresh_list_page();
        }
    }
}

void campus_ui_key(campus_key_t key) {
    // 先用最新的列表对齐游标，再按键 —— 见 rebuild_sorted 的说明。
    rebuild_sorted();

    if (!s_in_detail) {
        if (key == CAMPUS_KEY_UP) {
            if (s_cursor > 0) {
                s_cursor--;
            }
        } else if (key == CAMPUS_KEY_DOWN) {
            if (s_cursor + 1 < s_sorted_count) {
                s_cursor++;
            }
        } else if (key == CAMPUS_KEY_OK) {
            if (current_todo() != NULL) {
                show_detail(true);
                return;
            }
        }
        s_dirty = true;
        return;
    }

    // 详情页
    if (key == CAMPUS_KEY_UP) {
        if (s_action > 0) {
            s_action--;
        }
    } else if (key == CAMPUS_KEY_DOWN) {
        if (s_action < 2) {
            s_action++;
        }
    } else if (key == CAMPUS_KEY_OK) {
        const campus_todo_t *todo = current_todo();
        if (todo == NULL || s_op_cb == NULL) {
            show_detail(false);
            return;
        }
        uint32_t id = todo->id;
        if (s_action == 0) {
            // 已完成的再按就是「标记未完成」——按钮语义跟着状态走，与界面文案一致。
            bool done = todo->status == CAMPUS_STATUS_DONE;
            s_op_cb(id, done ? CAMPUS_OP_UNDONE : CAMPUS_OP_DONE, 0);
        } else if (s_action == 1) {
            s_op_cb(id, CAMPUS_OP_SNOOZE, 60);
        } else {
            show_detail(false);
            return;
        }
        // 本地立刻反映，不等手机回包：设备是弱网端，操作要即时可见。
        show_detail(false);
    } else if (key == CAMPUS_KEY_OK_LONG) {
        show_detail(false);
    }
    s_dirty = true;
}
