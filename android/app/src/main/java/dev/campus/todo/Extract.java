package dev.campus.todo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型返回的 JSON 变成待办，并且**不信模型**。
 *
 * 硬规则（任何一条不过就降级或丢弃，绝不静默放过）：
 *   1. evidence 必须能在原文里找到 —— 防幻觉的主闸门，找不到直接丢；
 *   2. due_iso 必须能解析且落在 [now-1d, now+400d]；
 *   3. due_text 含“今天/明天/后天”时，推断出的日期必须与它一致，否则置为不确定；
 *   4. 置信度低于阈值或日期不确定 → 进“待确认”，不自动建提醒。
 *
 * 纯逻辑：只用 org.json 与 java.time，不碰 Android API，可在 JVM 上单测。
 */
public final class Extract {

    private Extract() {
    }

    /** 允许比“现在”早 1 天（跨零点刚过的情况）。 */
    public static final long MIN_AHEAD_MS = -24L * 3600L * 1000L;
    /** 允许最远 400 天。 */
    public static final long MAX_AHEAD_MS = 400L * 24L * 3600L * 1000L;
    public static final double MIN_CONFIDENCE = 0.6;
    public static final int MAX_TITLE = 80;
    public static final int MAX_ITEMS = 20;

    private static final Set<String> KINDS =
            new HashSet<>(Arrays.asList("homework", "exam", "notice", "info", "other"));

    public static final class Result {
        public final List<Model.Todo> accepted = new ArrayList<>();
        public final List<Model.Todo> pending = new ArrayList<>();
        /** 参考信息（班级通知等）：单独一条路，不进待办也不进待确认。 */
        public final List<Model.Todo> info = new ArrayList<>();
        public int dropped;
        public int overLimit;
        /** null 表示成功。 */
        public String error;

        public boolean ok() {
            return error == null;
        }

        public int total() {
            return accepted.size() + pending.size() + info.size();
        }
    }

    public static Result parse(String content, String sourceText, long nowMillis) {
        return parse(content, sourceText, nowMillis, ZoneId.systemDefault(), null);
    }

    public static Result parse(String content, String sourceText, long nowMillis, ZoneId zone) {
        return parse(content, sourceText, nowMillis, zone, null);
    }

    /**
     * @param sourceTimes 本批每条通知的**到达时间**，与 src 序号一一对应；可传 null。
     *                    相对日期必须以它为准：10-06 收到“明天开班会”、10-08 才整理，
     *                    正确答案是 10-07 而不是 10-09。
     */
    public static Result parse(String content, String sourceText, long nowMillis, ZoneId zone,
                               long[] sourceTimes) {
        Result result = new Result();

        if (content == null || content.trim().isEmpty()) {
            // 官方文档明确说明：JSON Output 偶尔会返回空 content。这不是“没有待办”。
            result.error = "模型返回了空内容（JSON Output 已知会偶发），已按失败处理";
            return result;
        }

        JSONObject root;
        try {
            root = new JSONObject(content);
        } catch (JSONException e) {
            result.error = "模型返回的不是合法 JSON：" + e.getMessage();
            return result;
        }

        JSONArray items = root.optJSONArray("items");
        if (items == null) {
            result.error = "模型返回里缺少 items 数组";
            return result;
        }

        String haystack = Filter.normalize(sourceText);
        int limit = Math.min(items.length(), MAX_ITEMS);
        result.overLimit = Math.max(0, items.length() - MAX_ITEMS);

        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                result.dropped++;
                continue;
            }

            String title = Filter.clean(item.optString("title", "")).trim();
            if (title.isEmpty()) {
                result.dropped++;
                continue;
            }
            if (title.length() > MAX_TITLE) {
                title = title.substring(0, MAX_TITLE);
            }

            String evidence = Filter.clean(item.optString("evidence", "")).trim();
            String needle = Filter.normalize(evidence);
            if (needle.isEmpty() || !haystack.contains(needle)) {
                // 原文里找不到依据 —— 这条是编的。
                result.dropped++;
                continue;
            }

            Model.Todo todo = new Model.Todo();
            todo.title = title;
            todo.evidence = evidence;
            todo.source = "";
            todo.srcIndex = item.optInt("src", 0);
            todo.course = Filter.clean(item.optString("course", "")).trim();
            if (todo.course.length() > 20) {
                todo.course = todo.course.substring(0, 20);
            }

            String kind = item.optString("kind", "other").trim();
            todo.kind = KINDS.contains(kind) ? kind : "other";
            todo.dueText = Filter.clean(item.optString("due_text", "")).trim();

            if (Model.KIND_INFO.equals(todo.kind)) {
                // 参考信息只过 evidence 闸门，不判日期、也不判置信度：
                // 它本来就没有「截止时间」这个概念，硬塞进待确认只会把待办列表搅浑。
                todo.dueAt = 0;
                todo.status = Model.STATUS_INFO;
                result.info.add(todo);
                continue;
            }

            double confidence = item.optDouble("confidence", 0d);
            if (Double.isNaN(confidence)) {
                confidence = 0d;
            }
            todo.confidence = Math.max(0d, Math.min(1d, confidence));

            String dueIso = item.isNull("due_iso") ? "" : item.optString("due_iso", "").trim();
            long dueAt = parseDue(dueIso, zone);
            if (dueAt != 0) {
                if (dueAt < nowMillis + MIN_AHEAD_MS || dueAt > nowMillis + MAX_AHEAD_MS) {
                    dueAt = 0;   // 越界，不可信
                } else if (contradictsRelativeDay(todo.dueText, todo.evidence, dueAt,
                        referenceTime(sourceTimes, todo.srcIndex, nowMillis), zone)) {
                    dueAt = 0;   // 与原文时间表述冲突
                }
            }
            todo.dueAt = dueAt;

            if (dueAt == 0 || todo.confidence < MIN_CONFIDENCE) {
                todo.status = Model.STATUS_PENDING;
                result.pending.add(todo);
            } else {
                todo.status = Model.STATUS_ACTIVE;
                result.accepted.add(todo);
            }
        }

        return result;
    }

    /** 支持带时区、不带时区、以及只有日期三种写法；解析不了返回 0。 */
    static long parseDue(String iso, ZoneId zone) {
        if (iso == null || iso.isEmpty()) {
            return 0;
        }
        String text = iso.trim();
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它写法
        }
        try {
            return ZonedDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }

    /** 原文里的相对日：-1 表示看不出来，0/1/2 表示今天/明天/后天。 */
    static int relativeDay(String dueText) {
        if (dueText == null || dueText.isEmpty()) {
            return -1;
        }
        if (dueText.contains("后天")) {
            return 2;
        }
        if (dueText.contains("明天") || dueText.contains("明日")) {
            return 1;
        }
        if (dueText.contains("今天") || dueText.contains("今日") || dueText.contains("今晚")) {
            return 0;
        }
        return -1;
    }

    /** 取该条待办对应通知的到达时间；拿不到就退回整理时刻。 */
    static long referenceTime(long[] sourceTimes, int srcIndex, long nowMillis) {
        if (sourceTimes != null && srcIndex >= 1 && srcIndex <= sourceTimes.length
                && sourceTimes[srcIndex - 1] > 0) {
            return sourceTimes[srcIndex - 1];
        }
        return nowMillis;
    }

    /**
     * 只在原文给了明确的相对日、且与推断出的日期不一致时才判冲突。
     * “下周三”“本周内”这类表述歧义太大，交给置信度与待确认去兜。
     *
     * 相对日的信号同时看 due_text 与 evidence：due_text 是模型自己填的，它留空就能绕过
     * 校验；evidence 必须逐字出现在原文里，绕不过去。
     *
     * @param referenceMillis 该条通知的**到达时间**，不是整理时刻。
     */
    static boolean contradictsRelativeDay(String dueText, String evidence, long dueAt,
                                          long referenceMillis, ZoneId zone) {
        int rel = relativeDay(dueText);
        if (rel < 0) {
            rel = relativeDay(evidence);
        }
        if (rel < 0) {
            return false;
        }
        LocalDate wantDay = LocalDate.ofInstant(
                java.time.Instant.ofEpochMilli(referenceMillis), zone).plusDays(rel);
        LocalDate gotDay = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(dueAt), zone);
        return !wantDay.equals(gotDay);
    }
}
