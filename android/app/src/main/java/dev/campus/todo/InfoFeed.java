package dev.campus.todo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 班级通知列表的「按天分组」视图模型。
 *
 * 纯逻辑：不碰 Android API，可以直接在 JVM 上单测（见 InfoFeedTest）。
 * 分组本身是有边界的活（跨零点、跨天顺序、空列表），所以它不写在 Adapter 里。
 */
public final class InfoFeed {

    private InfoFeed() {
    }

    /** 一行：要么是日期小标题，要么是一条通知。 */
    public static final class Row {
        public final boolean header;
        public final String label;
        public final Model.Todo todo;

        private Row(boolean header, String label, Model.Todo todo) {
            this.header = header;
            this.label = label;
            this.todo = todo;
        }

        static Row header(String label) {
            return new Row(true, label, null);
        }

        static Row item(Model.Todo todo) {
            return new Row(false, null, todo);
        }
    }

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * @param todos 必须已按时间倒序（Db.listInfo 就是这么给的）
     */
    public static List<Row> build(List<Model.Todo> todos, long nowMillis, ZoneId zone) {
        List<Row> rows = new ArrayList<>();
        if (todos == null || todos.isEmpty()) {
            return rows;
        }
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone);
        LocalDate current = null;

        for (Model.Todo todo : todos) {
            if (todo == null) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(todo.createdAt), zone);
            if (!day.equals(current)) {
                current = day;
                rows.add(Row.header(dayLabel(day, today)));
            }
            rows.add(Row.item(todo));
        }
        return rows;
    }

    /** 今天 / 昨天 / 明天，再往前就直接给 MM-dd。 */
    static String dayLabel(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "今天";
        }
        if (day.equals(today.minusDays(1))) {
            return "昨天";
        }
        if (day.equals(today.plusDays(1))) {
            return "明天";
        }
        return day.format(MONTH_DAY);
    }
}
