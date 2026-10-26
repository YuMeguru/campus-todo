package dev.campus.todo;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

/**
 * 自动整理任务。
 *
 * JobService 的回调跑在主线程，所以真正的活在子线程做、做完再 jobFinished ——
 * 在 onStartJob 里阻塞会直接拖死系统调度。
 *
 * 省钱的两个前提已经由下层保证：没有新消息时 Organizer 不会发请求；
 * 撞到每日上限时它会直接返回错误。这里只负责「到点了就试一次」。
 */
public final class AutoJobService extends JobService {

    private static final String TAG = "AutoOrganize";

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();

        // 归档只看本地库、不联网，顺手做掉，也不依赖 API Key。
        // 放在最前面：即使这次整理因为没 Key 被跳过，该收起来的条目也照样收。
        try {
            Db.get(ctx).archiveDue(System.currentTimeMillis(), Db.ARCHIVE_WINDOW);
        } catch (Throwable t) {
            Log.e(TAG, "归档失败", t);
        }

        if (Prefs.autoIntervalMinutes(ctx) < AutoOrganizer.MIN_MINUTES) {
            return false;   // 用户已经关掉了，这次任务作废
        }
        if (!KeyVault.has(ctx)) {
            Log.i(TAG, "没有 API Key，自动整理跳过");
            return false;
        }

        new Thread(() -> {
            try {
                Organizer.Summary s = Organizer.run(ctx, System.currentTimeMillis());
                if (s.error != null) {
                    Log.i(TAG, "自动整理未执行：" + s.error);
                } else {
                    Log.i(TAG, "自动整理：消息 " + s.collected + " 条 → 新待办 " + s.accepted
                            + "，待确认 " + s.pending + "，剩余未整理 " + s.remaining);
                }
            } catch (Throwable t) {
                Log.e(TAG, "自动整理异常", t);
            } finally {
                jobFinished(params, false);
            }
        }, "auto-organize").start();

        return true;   // 还在干活，干完会调 jobFinished
    }

    /** 被系统打断（例如网断了）就返回 true，按周期重排一次。 */
    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
