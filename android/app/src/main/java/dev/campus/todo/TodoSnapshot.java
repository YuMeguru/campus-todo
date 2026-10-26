package dev.campus.todo;

import java.util.ArrayList;
import java.util.List;

/**
 * campus-sync/1 的载荷格式（文本）。纯逻辑，可单测（见 TodoSnapshotTest）。
 *
 * <pre>
 *   campus-sync/1
 *   T\t&lt;id&gt;\t&lt;dueAt&gt;\t&lt;snoozeUntil&gt;\t&lt;status&gt;\t&lt;source&gt;\t&lt;convo&gt;\t&lt;title&gt;
 * </pre>
 *
 * 为什么载荷用文本而不是二进制：定长二进制省不了几个字节，却让协议没法用现成 BLE 工具肉眼核对。
 * 分帧、长度和完整性由 BleFrame 负责，这里只管「一行一条待办」。
 *
 * 分隔符是制表符与换行，所以字段值里的分隔符必须被替换掉 —— 否则一条带换行的公告
 * 就能把整包拆成两条假记录。这是这个格式唯一的硬约束。
 */
public final class TodoSnapshot {

    private TodoSnapshot() {
    }

    public static final String HEADER = "campus-sync/1";
    private static final char FIELD = '\t';
    private static final char LINE = '\n';
    /** 设备屏小，标题没必要传全书；正文里的关键信息在 convo 与 title 前段。 */
    private static final int MAX_TITLE = 120;

    public static String encode(List<Model.Todo> todos) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append(LINE);
        if (todos != null) {
            for (Model.Todo todo : todos) {
                if (todo == null) {
                    continue;
                }
                sb.append('T').append(FIELD)
                        .append(todo.id).append(FIELD)
                        .append(todo.dueAt).append(FIELD)
                        .append(todo.snoozeUntil).append(FIELD)
                        .append(field(todo.status)).append(FIELD)
                        .append(field(todo.source)).append(FIELD)
                        .append(field(todo.convo)).append(FIELD)
                        .append(field(clip(todo.title)))
                        .append(LINE);
            }
        }
        return sb.toString();
    }

    public static final class Decoded {
        public final List<Model.Todo> todos = new ArrayList<>();
        public int skipped;
        /** null 表示成功。 */
        public String error;

        public boolean ok() {
            return error == null;
        }
    }

    public static Decoded decode(String text) {
        Decoded out = new Decoded();
        if (text == null || text.trim().isEmpty()) {
            out.error = "快照为空";
            return out;
        }

        String[] lines = text.split(String.valueOf(LINE), -1);
        int start = 0;
        while (start < lines.length && lines[start].trim().isEmpty()) {
            start++;
        }
        if (start >= lines.length || !HEADER.equals(lines[start].trim())) {
            out.error = "缺少 " + HEADER + " 头";
            return out;
        }

        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            // -1：保留尾部空字段，否则「标题为空」这种行会被误判成字段不够。
            String[] parts = line.split(String.valueOf(FIELD), -1);
            if (parts.length < 8 || !"T".equals(parts[0].trim())) {
                out.skipped++;
                continue;
            }
            try {
                Model.Todo todo = new Model.Todo();
                todo.id = Long.parseLong(parts[1].trim());
                todo.dueAt = Long.parseLong(parts[2].trim());
                todo.snoozeUntil = Long.parseLong(parts[3].trim());
                todo.status = parts[4];
                todo.source = parts[5];
                todo.convo = parts[6];
                todo.title = parts[7];
                out.todos.add(todo);
            } catch (NumberFormatException e) {
                out.skipped++;
            }
        }
        return out;
    }

    // -------------------------------------------------------------- 设备操作

    public static String encodeOp(long todoId, String action, int snoozeMinutes, int devSeq) {
        return todoId + String.valueOf(FIELD)
                + field(action) + FIELD
                + snoozeMinutes + FIELD
                + devSeq;
    }

    public static Model.DeviceOp decodeOp(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(String.valueOf(FIELD), -1);
        if (parts.length < 4) {
            return null;
        }
        try {
            Model.DeviceOp op = new Model.DeviceOp();
            op.todoId = Long.parseLong(parts[0].trim());
            op.action = parts[1].trim();
            op.snoozeMinutes = Integer.parseInt(parts[2].trim());
            op.devSeq = Integer.parseInt(parts[3].trim());
            if (op.action.isEmpty()) {
                return null;
            }
            return op;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 分隔符出现在字段里就换成空格，并去掉首尾空白。 */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append((c == FIELD || c == LINE || c == '\r') ? ' ' : c);
        }
        return sb.toString().trim();
    }

    private static String clip(String value) {
        String clean = field(value);
        return clean.length() > MAX_TITLE ? clean.substring(0, MAX_TITLE) : clean;
    }
}
