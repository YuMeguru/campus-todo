package dev.campus.todo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地库。用平台自带的 SQLiteOpenHelper，不引 Room：
 * 四张表的小库不值得为它拉进注解处理器和一堆 androidx。
 */
public final class Db extends SQLiteOpenHelper {

    public static final String NAME = "campus.db";
    public static final int VERSION = 5;

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) {
            instance = new Db(ctx.getApplicationContext());
        }
        return instance;
    }

    private Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE raw ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "pkg TEXT NOT NULL,"
                + "label TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL DEFAULT '',"
                + "body TEXT NOT NULL DEFAULT '',"
                + "posted_at INTEGER NOT NULL,"
                + "state TEXT NOT NULL DEFAULT 'new',"
                + "ingest TEXT NOT NULL DEFAULT 'auto',"
                + "fingerprint TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE INDEX idx_raw_state ON raw(state)");

        db.execSQL("CREATE TABLE todo ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "course TEXT NOT NULL DEFAULT '',"
                + "kind TEXT NOT NULL DEFAULT 'other',"
                + "source TEXT NOT NULL DEFAULT '',"
                + "convo TEXT NOT NULL DEFAULT '',"
                + "due_text TEXT NOT NULL DEFAULT '',"
                + "due_at INTEGER NOT NULL DEFAULT 0,"
                + "evidence TEXT NOT NULL DEFAULT '',"
                + "confidence REAL NOT NULL DEFAULT 0,"
                + "status TEXT NOT NULL DEFAULT 'active',"
                + "created_at INTEGER NOT NULL,"
                + "snooze_until INTEGER NOT NULL DEFAULT 0,"
                + "completed_at INTEGER NOT NULL DEFAULT 0,"
                + "archived_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_todo_status ON todo(status)");

        db.execSQL("CREATE TABLE run ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "at INTEGER NOT NULL,"
                + "http_status INTEGER NOT NULL DEFAULT 0,"
                + "in_tokens INTEGER NOT NULL DEFAULT 0,"
                + "out_tokens INTEGER NOT NULL DEFAULT 0,"
                + "cache_tokens INTEGER NOT NULL DEFAULT 0,"
                + "outcome TEXT NOT NULL DEFAULT '',"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "attempts INTEGER NOT NULL DEFAULT 1)");

        // 采集规则：按会话名（群名/联系人名）归一化子串匹配，黑名单优先。
        db.execSQL("CREATE TABLE rule ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "mode TEXT NOT NULL,"
                + "text TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "UNIQUE(mode, text))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 只加列，不 DROP：用户已经采集到的消息和待办不能因为一次升级丢掉。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE todo ADD COLUMN snooze_until INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE run ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE rule ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "mode TEXT NOT NULL,"
                    + "text TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "UNIQUE(mode, text))");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE todo ADD COLUMN convo TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE todo ADD COLUMN completed_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE todo ADD COLUMN archived_at INTEGER NOT NULL DEFAULT 0");
            // 升级前就完成的条目没有完成时间，用创建时间兜底 ——
            // 否则它们永远等不到「完成满一天」这个条件，会一直挂在列表里。
            db.execSQL("UPDATE todo SET completed_at = created_at WHERE status = 'done'");
        }
    }

    // ------------------------------------------------------------------ raw

    /** 指纹重复时返回 -1（同一条通知被系统重复回调）。 */
    public long insertRaw(Model.Raw r) {
        ContentValues v = new ContentValues();
        v.put("pkg", r.pkg);
        v.put("label", r.pkgLabel);
        v.put("title", r.title);
        v.put("body", r.body);
        v.put("posted_at", r.postedAt);
        v.put("state", r.state);
        v.put("ingest", r.ingest);
        v.put("fingerprint", Filter.hash(r.pkg, r.title, r.body));
        return getWritableDatabase().insertWithOnConflict(
                "raw", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * 一次查询拿主页要的两个计数（待整理消息、班级通知）。
     * 主页每次刷新都要这两个数，拆成两条独立查询就是两次主线程往返。
     * 返回 {待整理消息, 班级通知}。
     */
    public long[] homeCounts() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT (SELECT COUNT(*) FROM raw WHERE state = 'new'),"
                        + " (SELECT COUNT(*) FROM todo WHERE status = 'info')", null);
        try {
            if (c.moveToFirst()) {
                return new long[]{c.getLong(0), c.getLong(1)};
            }
        } finally {
            c.close();
        }
        return new long[]{0, 0};
    }

    public int countRaw(String state) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM raw WHERE state = ?", new String[]{state});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 返回 {待整理, 已过滤, 已整理}。一条查询搞定，避免主线程上三次往返。 */
    public long[] rawCounts() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(state = 'new'), SUM(state = 'skip'), SUM(state = 'done') FROM raw",
                null);
        try {
            if (c.moveToFirst()) {
                return new long[]{c.getLong(0), c.getLong(1), c.getLong(2)};
            }
        } finally {
            c.close();
        }
        return new long[]{0, 0, 0};
    }

    public List<Model.Raw> pendingRaw(int limit) {
        return queryRaw("SELECT * FROM raw WHERE state = 'new' ORDER BY posted_at ASC LIMIT "
                + Math.max(1, limit));
    }

    public List<Model.Raw> recentRaw(int limit) {
        return queryRaw("SELECT * FROM raw ORDER BY posted_at DESC LIMIT " + Math.max(1, limit));
    }

    private List<Model.Raw> queryRaw(String sql) {
        List<Model.Raw> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                Model.Raw r = new Model.Raw();
                r.id = c.getLong(c.getColumnIndexOrThrow("id"));
                r.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
                r.pkgLabel = c.getString(c.getColumnIndexOrThrow("label"));
                r.title = c.getString(c.getColumnIndexOrThrow("title"));
                r.body = c.getString(c.getColumnIndexOrThrow("body"));
                r.postedAt = c.getLong(c.getColumnIndexOrThrow("posted_at"));
                r.state = c.getString(c.getColumnIndexOrThrow("state"));
                r.ingest = c.getString(c.getColumnIndexOrThrow("ingest"));
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteRaw(long id) {
        getWritableDatabase().delete("raw", "id = ?", new String[]{String.valueOf(id)});
    }

    public void markRaw(long id, String state) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        getWritableDatabase().update("raw", v, "id = ?", new String[]{String.valueOf(id)});
    }

    public void markRaws(List<Long> ids, String state) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("state", state);
            for (Long id : ids) {
                db.update("raw", v, "id = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----------------------------------------------------------------- todo

    public long insertTodo(Model.Todo t) {
        ContentValues v = new ContentValues();
        v.put("title", t.title);
        v.put("course", t.course);
        v.put("kind", t.kind);
        v.put("source", t.source);
        v.put("convo", t.convo);
        v.put("due_text", t.dueText);
        v.put("due_at", t.dueAt);
        v.put("evidence", t.evidence);
        v.put("confidence", t.confidence);
        v.put("status", t.status);
        v.put("created_at", t.createdAt);
        v.put("snooze_until", t.snoozeUntil);
        long id = getWritableDatabase().insert("todo", null, v);
        t.id = id;
        return id;
    }

    /** 同标题且未完成的待办已经存在时不去重插入，避免重复提醒。info 不参与这个判断。 */
    public boolean hasOpenTodo(String title) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM todo WHERE title = ? AND status NOT IN ('done', 'info') LIMIT 1",
                new String[]{title});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public List<Model.Todo> listTodos() {
        // info（班级通知）和 archived（归档区）都不进主列表，各有各的地方。
        return queryTodo("SELECT * FROM todo WHERE status NOT IN ('info', 'archived') ORDER BY "
                + "CASE status WHEN 'active' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END,"
                + "CASE WHEN due_at = 0 THEN 1 ELSE 0 END, due_at ASC, id DESC");
    }

    /** 通知类参考信息：按时间倒序，不参与待办排序，也不排提醒。 */
    public List<Model.Todo> listInfo() {
        return queryTodo("SELECT * FROM todo WHERE status = 'info' "
                + "ORDER BY created_at DESC, id DESC");
    }

    public List<Model.Todo> activeWithDue() {
        return queryTodo("SELECT * FROM todo WHERE status = 'active' AND due_at > 0");
    }

    public Model.Todo getTodo(long id) {
        List<Model.Todo> one = queryTodo("SELECT * FROM todo WHERE id = " + id + " LIMIT 1");
        return one.isEmpty() ? null : one.get(0);
    }

    private List<Model.Todo> queryTodo(String sql) {
        List<Model.Todo> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                Model.Todo t = new Model.Todo();
                t.id = c.getLong(c.getColumnIndexOrThrow("id"));
                t.title = c.getString(c.getColumnIndexOrThrow("title"));
                t.course = c.getString(c.getColumnIndexOrThrow("course"));
                t.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                t.source = c.getString(c.getColumnIndexOrThrow("source"));
                t.convo = c.getString(c.getColumnIndexOrThrow("convo"));
                t.dueText = c.getString(c.getColumnIndexOrThrow("due_text"));
                t.dueAt = c.getLong(c.getColumnIndexOrThrow("due_at"));
                t.evidence = c.getString(c.getColumnIndexOrThrow("evidence"));
                t.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
                t.status = c.getString(c.getColumnIndexOrThrow("status"));
                t.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                t.snoozeUntil = c.getLong(c.getColumnIndexOrThrow("snooze_until"));
                t.completedAt = c.getLong(c.getColumnIndexOrThrow("completed_at"));
                t.archivedAt = c.getLong(c.getColumnIndexOrThrow("archived_at"));
                out.add(t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void setTodoStatus(long id, String status) {
        setTodoStatus(id, status, System.currentTimeMillis());
    }

    /**
     * 改状态，顺带维护完成时间。
     *
     * 只有进入 done 才记 completed_at，改回 active / pending 时一并清零 ——
     * 否则「完成 → 撤回 → 再完成」会沿用上一次的旧时间，归档时点就算错了。
     */
    public void setTodoStatus(long id, String status, long now) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("completed_at", Model.STATUS_DONE.equals(status) ? now : 0);
        getWritableDatabase().update("todo", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /**
     * 编辑：标题 / 课程 / 截止时间 / 状态一次写完，避免连续几次 update。
     * 「待确认」的条目一旦被给了确定时间，就由调用方把 status 一起改成 active。
     */
    public void updateTodo(long id, String title, String course, long dueAt, String status) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("course", course);
        v.put("due_at", dueAt);
        v.put("status", status);
        getWritableDatabase().update("todo", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** 「稍后提醒」只改这一个字段，绝不碰 due_at：截止时间是事实，不能因为点一下稍后就没了。 */
    public void setTodoSnooze(long id, long snoozeUntil) {
        ContentValues v = new ContentValues();
        v.put("snooze_until", snoozeUntil);
        getWritableDatabase().update("todo", v, "id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTodo(long id) {
        getWritableDatabase().delete("todo", "id = ?", new String[]{String.valueOf(id)});
    }

    // -------------------------------------------------------------- 归档区

    /** 归档窗口：完成或过期满这么久之后才收起来。 */
    public static final long ARCHIVE_WINDOW = 24L * 3600_000L;

    /**
     * 把到点的条目归档，返回归档条数。
     *
     * 两条规则：完成满一天、过期满一天。都只是改 status，一行数据都不删 ——
     * 归档是「从列表里收起来」，用户随时能恢复。
     *
     * `archived_at = 0` 保证每条只被扫一次；用户手动恢复过的 archived_at 是 -1，
     * 不会被自动归档第二次。
     */
    public int archiveDue(long now, long window) {
        ContentValues v = new ContentValues();
        v.put("status", Model.STATUS_ARCHIVED);
        v.put("archived_at", now);

        int done = getWritableDatabase().update("todo", v,
                "status = 'done' AND archived_at = 0"
                        + " AND completed_at > 0 AND completed_at <= ?",
                new String[]{String.valueOf(now - window)});

        int overdue = getWritableDatabase().update("todo", v,
                "status = 'active' AND archived_at = 0"
                        + " AND due_at > 0 AND due_at <= ?",
                new String[]{String.valueOf(now - window)});

        return done + overdue;
    }

    /** 归档区列表：最近归档的排前面。 */
    public List<Model.Todo> listArchived() {
        return queryTodo("SELECT * FROM todo WHERE status = 'archived'"
                + " ORDER BY archived_at DESC, id DESC");
    }

    public int countArchived() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM todo WHERE status = 'archived'", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 从归档里恢复。
     *
     * 恢复成「已完成」还是「进行中」，看它当初怎么进去的：有完成时间的就是做完的，
     * 否则是过期没做的。
     *
     * 恢复后 archived_at 置 -1：过期条目不会因为被捞回来就自称「已完成」，
     * 但也不该隔一天又被自动收走 —— 用户明确捞回来的东西得留在列表里。
     */
    public void restoreTodo(long id) {
        long completedAt = 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT completed_at FROM todo WHERE id = ?",
                new String[]{String.valueOf(id)});
        try {
            if (c.moveToFirst()) {
                completedAt = c.getLong(0);
            }
        } finally {
            c.close();
        }

        ContentValues v = new ContentValues();
        v.put("status", completedAt > 0 ? Model.STATUS_DONE : Model.STATUS_ACTIVE);
        v.put("archived_at", Model.Todo.RESTORED);
        getWritableDatabase().update("todo", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** 用户手动清除：这一步才是真的删。调用方负责先弹确认。 */
    public void purgeArchived() {
        getWritableDatabase().delete("todo", "status = 'archived'", null);
    }

    // ------------------------------------------------------------------ run

    /**
     * @param attempts 这次「整理」实际发出的 HTTP 请求数（含退避重试）。
     *                 按次数封顶必须用它求和，只数行数会把重试漏掉。
     */
    public void logRun(int httpStatus, int inTokens, int outTokens, int cacheTokens,
                       String outcome, long durationMs, int attempts) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis());
        v.put("http_status", httpStatus);
        v.put("in_tokens", inTokens);
        v.put("out_tokens", outTokens);
        v.put("cache_tokens", cacheTokens);
        v.put("outcome", outcome == null ? "" : outcome);
        v.put("duration_ms", durationMs);
        v.put("attempts", Math.max(1, attempts));
        getWritableDatabase().insert("run", null, v);
    }

    /**
     * 返回 {实际请求数, 输入 token, 输出 token}，统计范围是本地时区的今天。
     * 请求数用 SUM(attempts) 而不是 COUNT(*)：一次整理重试 3 次就是 3 个请求。
     */
    public long[] todayUsage() {
        long start = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT IFNULL(SUM(attempts),0), IFNULL(SUM(in_tokens),0), "
                        + "IFNULL(SUM(out_tokens),0) FROM run WHERE at >= ?",
                new String[]{String.valueOf(start)});
        try {
            if (c.moveToFirst()) {
                return new long[]{c.getLong(0), c.getLong(1), c.getLong(2)};
            }
        } finally {
            c.close();
        }
        return new long[]{0, 0, 0};
    }

    // ----------------------------------------------------------------- rule

    /** 重复的规则会被忽略（UNIQUE(mode, text)）；已存在时返回 -1。 */
    public long addRule(String mode, String text) {
        ContentValues v = new ContentValues();
        v.put("mode", mode);
        v.put("text", text);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "rule", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 黑名单排前面，方便界面分组显示。 */
    public List<Model.Rule> listRules() {
        List<Model.Rule> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, mode, text FROM rule ORDER BY mode DESC, id ASC", null);
        try {
            while (c.moveToNext()) {
                Model.Rule r = new Model.Rule();
                r.id = c.getLong(0);
                r.mode = c.getString(1);
                r.text = c.getString(2);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteRule(long id) {
        getWritableDatabase().delete("rule", "id = ?", new String[]{String.valueOf(id)});
    }

    /** 只清空班级通知，不动待办。 */
    public void clearInfo() {
        getWritableDatabase().delete("todo", "status = 'info'", null);
    }

    public void wipe() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM raw");
        db.execSQL("DELETE FROM todo");
        db.execSQL("DELETE FROM run");
        db.execSQL("DELETE FROM rule");
    }
}
