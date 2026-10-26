package dev.campus.todo;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/**
 * 自动整理的定时器，用平台自带的 JobScheduler：
 * 不引 WorkManager（那要拉一堆 androidx），也不需要前台服务与常驻通知。
 */
public final class AutoOrganizer {

    private AutoOrganizer() {
    }

    public static final int JOB_ID = 0x10D0;

    /** JobScheduler 对周期任务的硬下限就是 15 分钟，比它小会直接抛异常。 */
    public static final int MIN_MINUTES = 15;

    /** 界面上可选的档位（分钟）；0 = 关闭。 */
    public static final int[] CHOICES = {0, 15, 30, 60, 180, 360};

    public static String label(int minutes) {
        switch (minutes) {
            case 0:
                return "关闭";
            case 15:
                return "每 15 分钟";
            case 30:
                return "每 30 分钟";
            case 60:
                return "每 1 小时";
            case 180:
                return "每 3 小时";
            case 360:
                return "每 6 小时";
            default:
                return "每 " + minutes + " 分钟";
        }
    }

    public static void apply(Context ctx, int minutes) {
        Prefs.setAutoIntervalMinutes(ctx, minutes);

        JobScheduler scheduler = ctx.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID);
        if (minutes < MIN_MINUTES) {
            return;
        }

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(ctx, AutoJobService.class))
                // 没网就不唤醒：省电，也免得醒来发现发不出请求、白排一次周期。
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // 重启后依然有效（需要 RECEIVE_BOOT_COMPLETED 权限）。
                .setPersisted(true)
                .setPeriodic(TimeUnit.MINUTES.toMillis(minutes))
                .build();
        scheduler.schedule(job);
    }

    /** 以系统里真实排着的任务为准，而不是只看偏好值。 */
    public static boolean isScheduled(Context ctx) {
        JobScheduler scheduler = ctx.getSystemService(JobScheduler.class);
        return scheduler.getPendingJob(JOB_ID) != null;
    }
}
