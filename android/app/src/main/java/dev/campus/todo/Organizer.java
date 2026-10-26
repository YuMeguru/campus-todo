package dev.campus.todo;

import android.content.Context;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * “一键整理”的执行体：取待整理通知 → 合批 → 调 DeepSeek → 本地硬校验 → 落库 + 排提醒。
 *
 * 阻塞式，必须在工作线程调用（MainActivity 用单线程 Executor 调它）。
 * 有意不做自动整理：这一版把“用户主动点一下”当作唯一的触发入口，费用可控、行为可预期。
 */
public final class Organizer {

    private Organizer() {
    }

    public static final int BATCH = 10;
    public static final int DAILY_REQUESTS = 60;
    public static final int DAILY_IN_TOKENS = 300_000;
    public static final int DAILY_OUT_TOKENS = 60_000;

    public static final class Summary {
        public int collected;
        public int accepted;
        /** 本次新建的正式待办标题，按入库顺序；整理收尾时合成一条通知。 */
        public final List<String> newTitles = new ArrayList<>();
        public int pending;
        /** 参考信息（班级通知等）：入库但不排提醒、不进待办列表。 */
        public int info;
        /** 其中有多少条是本地兜底词表救回来的（模型当时把它们归进了 ignored）。 */
        public int kept;
        public int dropped;
        public int skipped;
        public int remaining;
        public String error;
        public String payload;

        public boolean ok() {
            return error == null;
        }
    }

    /** 只组装将要发送的正文，不发请求。设置页用它做“发送内容预览”。 */
    public static String preview(Context ctx, long nowMillis) {
        List<Model.Raw> batch = Db.get(ctx).pendingRaw(BATCH);
        if (batch.isEmpty()) {
            return "";
        }
        return DeepSeek.buildUserMessage(nowMillis, batch);
    }

    /** 自动任务与手动点击可能撞在一起；同一时刻只允许一次整理，免得重复计费。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static Summary run(Context ctx, long nowMillis) {
        if (!RUNNING.compareAndSet(false, true)) {
            Summary busy = new Summary();
            busy.error = "上一次整理还在进行中，稍等一下";
            return busy;
        }
        try {
            return runLocked(ctx, nowMillis);
        } finally {
            RUNNING.set(false);
        }
    }

    private static Summary runLocked(Context ctx, long nowMillis) {
        Summary s = new Summary();
        Db db = Db.get(ctx);

        String apiKey = KeyVault.load(ctx);
        if (apiKey.isEmpty()) {
            s.error = "还没有填 API Key，先到设置里填一个";
            return s;
        }

        long[] usage = db.todayUsage();
        if (usage[0] >= DAILY_REQUESTS) {
            s.error = "今天已经调用 " + usage[0] + " 次，达到每日上限（" + DAILY_REQUESTS + " 次）";
            return s;
        }
        if (usage[1] >= DAILY_IN_TOKENS || usage[2] >= DAILY_OUT_TOKENS) {
            s.error = "今天的 token 用量已达上限，明天再试";
            return s;
        }

        List<Model.Raw> batch = db.pendingRaw(BATCH);
        s.remaining = db.countRaw("new");
        if (batch.isEmpty()) {
            s.error = null;
            return s;
        }
        s.collected = batch.size();

        String userText = DeepSeek.buildUserMessage(nowMillis, batch);
        s.payload = userText;

        DeepSeek.Response resp = DeepSeek.complete(apiKey, userText);
        // 记 attempts 而不是 1：一次整理最多重试 3 次，每次都真的打到了服务器。
        // 只记一行会把「每日 60 次」变成「每天最多 180 次」。
        db.logRun(resp.httpStatus, resp.inTokens, resp.outTokens, resp.cacheTokens,
                resp.ok() ? "ok" : String.valueOf(resp.error), resp.durationMs, resp.attempts);

        if (!resp.ok()) {
            s.error = resp.error == null ? "调用失败" : resp.error;
            s.remaining = db.countRaw("new");
            return s;   // 消息保持 new，下次重试
        }

        List<Long> ids = new ArrayList<>();
        StringBuilder source = new StringBuilder();
        long[] sourceTimes = new long[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            Model.Raw raw = batch.get(i);
            ids.add(raw.id);
            source.append(raw.display()).append('\n');
            // src 序号是 1 起，所以这里按同序对齐：相对日期要以通知到达时间为基准。
            sourceTimes[i] = raw.postedAt;
        }

        Extract.Result parsed =
                Extract.parse(resp.content, source.toString(), nowMillis, ZoneId.systemDefault(),
                        sourceTimes);
        if (!parsed.ok()) {
            s.error = parsed.error;
            s.remaining = db.countRaw("new");
            return s;
        }
        s.dropped = parsed.dropped;

        for (Model.Todo todo : parsed.accepted) {
            save(ctx, db, todo, batch, nowMillis, s);
        }
        for (Model.Todo todo : parsed.pending) {
            save(ctx, db, todo, batch, nowMillis, s);
        }
        for (Model.Todo todo : parsed.info) {
            save(ctx, db, todo, batch, nowMillis, s);
        }

        // 本地兜底：模型没给出任何条目的通知里，只要看着像教务/后勤通知就直接保留成 info。
        // 这把「教室改到 313」这类事从「大概率保留」变成了「一定保留」。
        Set<Integer> covered = new HashSet<>();
        for (Model.Todo todo : parsed.accepted) {
            covered.add(todo.srcIndex);
        }
        for (Model.Todo todo : parsed.pending) {
            covered.add(todo.srcIndex);
        }
        for (Model.Todo todo : parsed.info) {
            covered.add(todo.srcIndex);
        }
        for (int index : InfoFallback.rescued(batch, covered)) {
            Model.Raw raw = batch.get(index - 1);
            Model.Todo rescued = new Model.Todo();
            // 群名进 convo、正文进 title：拼成一个字符串会让正文被截掉一半，
            // 而且在列表里看不出是哪个群发的。正文不再截到 MAX_TITLE，公告本来就长。
            rescued.convo = Filter.clean(raw.title);
            rescued.title = Filter.clean(raw.body);
            if (rescued.title.isEmpty()) {
                rescued.title = rescued.convo;
            }
            // 内容本来就逐字取自原文，evidence 用同一段文本，防幻觉闸门天然成立。
            rescued.evidence = rescued.title;
            rescued.kind = Model.KIND_INFO;
            rescued.status = Model.STATUS_INFO;
            rescued.source = raw.pkgLabel == null || raw.pkgLabel.isEmpty()
                    ? "未知" : raw.pkgLabel;
            rescued.createdAt = nowMillis;
            db.insertTodo(rescued);
            s.info++;
            s.kept++;
        }

        db.markRaws(ids, "done");
        s.remaining = db.countRaw("new");
        // 一次整理只发一条合并通知：提炼出多少条都只出声一次，没有新增就不出声。
        Reminder.postBatch(ctx, s.newTitles, s.accepted, s.pending);
        return s;
    }

    private static void save(Context ctx, Db db, Model.Todo todo, List<Model.Raw> batch,
                            long nowMillis, Summary s) {
        todo.source = "未知";
        todo.convo = "";
        if (todo.srcIndex >= 1 && todo.srcIndex <= batch.size()) {
            Model.Raw raw = batch.get(todo.srcIndex - 1);
            if (raw.pkgLabel != null && !raw.pkgLabel.isEmpty()) {
                todo.source = raw.pkgLabel;
            }
            todo.convo = Filter.clean(raw.title);
        }
        todo.createdAt = nowMillis;

        if (db.hasOpenTodo(todo.title)) {
            s.skipped++;
            return;
        }
        db.insertTodo(todo);
        if (todo.isInfo()) {
            s.info++;   // 参考信息：入库供以后查，但不排提醒、不进待办列表
            return;
        }
        if (todo.isPending()) {
            // 待确认的刻意不排提醒：时间都没定下来，弹出来只会打扰。
            s.pending++;
            return;
        }
        s.accepted++;
        s.newTitles.add(todo.title);
        if (todo.dueAt > 0) {
            Reminder.schedule(ctx, todo);
        }
    }
}
