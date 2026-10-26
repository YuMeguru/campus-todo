package dev.campus.todo;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 界面小工具与文案格式化。全部用 framework 的 View，不引任何 UI 库。 */
public final class Ui {

    // 整体深色：底色近黑、卡片稍亮、正文纯白。
    // 之前用的是浅色底 + DayNight 主题，手机一进深色模式，框架控件（Button/EditText）
    // 的文字会变成白色，压在硬编码的白卡片上就看不清了。现在两边都是深色，不会再撞。
    public static final int BG = 0xFF101215;
    public static final int CARD = 0xFF1B1E24;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA7B0BE;
    public static final int ACCENT = 0xFF6EA8FE;
    public static final int WARN = 0xFFFFB84D;
    public static final int DONE = 0xFF8B93A1;
    public static final int OK = 0xFF6DD58C;
    /** 已过期：深色底上要够亮才看得清，原来的深红不行。 */
    public static final int OVERDUE = 0xFFFF7A7A;

    private Ui() {
    }

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setLineSpacing(dp(c, 2), 1f);
        return tv;
    }

    public static Button button(Context c, CharSequence s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        // 主题已经是深色，按钮默认文字就是浅色；仍然显式写死，免得以后主题一换又撞色。
        b.setTextColor(TEXT);
        return b;
    }

    /** 输入框：文字与提示色都显式指定，否则在某些主题下会和背景同色。 */
    public static EditText input(Context c, CharSequence hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        return e;
    }

    public static CheckBox check(Context c, CharSequence label) {
        CheckBox b = new CheckBox(c);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        return b;
    }

    public static TextView section(Context c, CharSequence s) {
        TextView tv = text(c, s, 13, MUTED, true);
        tv.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return tv;
    }

    /** 来源标签的配色，深色底上都要够亮。 */
    public static int sourceColor(String source) {
        if (source == null) {
            return MUTED;
        }
        switch (source) {
            case "微信":
                return OK;
            case "钉钉":
                return ACCENT;
            case "学习通":
                return WARN;
            default:
                return MUTED;
        }
    }

    /** 待办的状态色：待确认=橙，已完成=灰，已过期=红，正常=蓝。 */
    public static int statusColor(Model.Todo t) {
        if (Model.STATUS_DONE.equals(t.status)) {
            return DONE;
        }
        if (t.isPending()) {
            return WARN;
        }
        if (t.dueAt > 0 && t.dueAt < System.currentTimeMillis()) {
            return OVERDUE;
        }
        return ACCENT;
    }

    public static String statusLabel(Model.Todo t) {
        if (Model.STATUS_DONE.equals(t.status)) {
            return "已完成";
        }
        if (t.isPending()) {
            return "待确认";
        }
        if (t.snoozeUntil > System.currentTimeMillis()) {
            return "已稍后";
        }
        if (t.dueAt > 0 && t.dueAt < System.currentTimeMillis()) {
            return "已过期";
        }
        return "进行中";
    }

    /**
     * 格式化器做成静态常量。
     *
     * 原来每次调用都 new SimpleDateFormat：列表 bind 一次就 new 一次，滑动时每帧十几次，
     * 而它的构造要做 Locale / NumberFormat 初始化 —— 这是滚动掉帧的直接来源。
     * DateTimeFormatter 是不可变且线程安全的，可以安全共享。
     */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static String format(DateTimeFormatter formatter, long millis) {
        return formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** 截止时间：有绝对时间就显示绝对时间，否则回落到原文表述。 */
    public static String dueLabel(Model.Todo t) {
        if (t.dueAt > 0) {
            return format(DATE_TIME, t.dueAt);
        }
        if (t.dueText != null && !t.dueText.isEmpty()) {
            return t.dueText;
        }
        return "时间待确认";
    }

    public static String kindLabel(String kind) {
        if (kind == null) {
            return "其它";
        }
        switch (kind) {
            case "homework":
                return "作业";
            case "exam":
                return "考试";
            case "notice":
                return "通知";
            case "info":
                return "通知";
            default:
                return "其它";
        }
    }

    public static String timeLabel(long millis) {
        return format(DATE_TIME, millis);
    }

    /** 只有时分。按天分组的列表里，日期已经由小标题给了。 */
    public static String clockLabel(long millis) {
        return format(CLOCK, millis);
    }

    /** 原始消息的状态标签与配色。 */
    public static String rawStateLabel(String state) {
        if ("new".equals(state)) {
            return "待整理";
        }
        if ("skip".equals(state)) {
            return "已过滤";
        }
        return "已整理";
    }

    public static int rawStateColor(String state) {
        if ("new".equals(state)) {
            return ACCENT;
        }
        if ("skip".equals(state)) {
            return DONE;
        }
        return OK;
    }

    public static TextView chip(Context c, CharSequence s, int color) {
        TextView tv = text(c, s, 11, color, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 4));
        return tv;
    }
}
