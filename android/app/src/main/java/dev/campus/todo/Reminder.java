package dev.campus.todo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** 本地提醒：不依赖设备、不依赖网络，纯手机系统能力。 */
public final class Reminder {

    public static final String CHANNEL = "todo_due";
    public static final String EXTRA_TITLE = "reminder_title";
    public static final String EXTRA_BODY = "reminder_body";

    private static final long HOUR = 3600L * 1000L;

    private static final int SLOT_DAY_BEFORE = 0;
    private static final int SLOT_DUE = 1;
    private static final int SLOT_SNOOZE = 2;
    private static final int SLOT_COUNT = 3;

    private Reminder() {
    }

    public static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("待办截止前的提醒");
        nm.createNotificationChannel(channel);
    }

    /**
     * 排提醒。三个槽位：0 = 提前一天，1 = 截止时刻，2 = 稍后提醒。
     *
     * 「稍后」在语义上只推迟**提醒**，不改 dueAt：截止时间是事实，手机上点一下按钮
     * 不能把它改掉。所以 snoozeUntil 在未来时，本轮只用槽位 2。
     */
    public static void schedule(Context ctx, Model.Todo todo) {
        if (todo == null || !todo.isOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        cancel(ctx, todo);

        if (todo.snoozeUntil > now + 60_000L) {
            set(ctx, todo, SLOT_SNOOZE, todo.snoozeUntil, "再提醒你一次", todo.title);
            return;
        }
        if (todo.dueAt <= 0) {
            return;
        }
        long dayBefore = todo.dueAt - 24 * HOUR;
        if (dayBefore > now + 60_000L) {
            set(ctx, todo, SLOT_DAY_BEFORE, dayBefore, "还有一个待办明天截止", todo.title);
        }
        if (todo.dueAt > now + 60_000L) {
            String when = todo.dueText == null || todo.dueText.isEmpty()
                    ? "截止时间到了" : todo.dueText;
            set(ctx, todo, SLOT_DUE, todo.dueAt, "待办截止：" + when, todo.title);
        }
    }

    public static void cancel(Context ctx, Model.Todo todo) {
        if (todo == null) {
            return;
        }
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        for (int slot = 0; slot <= SLOT_SNOOZE; slot++) {
            am.cancel(pending(ctx, todo.id, slot, "", ""));
        }
    }

    private static void set(Context ctx, Model.Todo todo, int slot, long at,
                            String title, String body) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(ctx, todo.id, slot, title, body);
        // API 31 起精确闹钟需要单独授权；没授权就用不精确的，仍然会响，只是可能晚几分钟。
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context ctx, long todoId, int slot,
                                         String title, String body) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_BODY, body);
        int code = (int) ((todoId * SLOT_COUNT + slot) & 0x7FFFFFFFL);
        return PendingIntent.getBroadcast(ctx, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void post(Context ctx, String title, String body) {
        ensureChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title == null ? "待办提醒" : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), notification);
    }

    // ---------------------------------------------------------------- 整理结果

    public static final String CHANNEL_BATCH = "todo_batch";

    /**
     * 固定通知 ID：后一次整理直接覆盖前一次。
     * 整理是周期性动作，每次换一个 ID 会把通知栏堆成流水账；用户要的是
     * “有新的待办”这一条，不是每次整理各留一条。
     */
    private static final int NOTIF_BATCH = 0x7A11;

    /** 正文最多列几条，超出的折成「等 N 条」。 */
    private static final int BATCH_LINES = 4;

    public static void ensureBatchChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_BATCH) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_BATCH, "整理结果", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("每次整理出新待办后发一条");
        nm.createNotificationChannel(channel);
    }

    /**
     * 一次整理合并成一条通知：标题给总数，正文列前几条标题。
     *
     * 只在真有新增时才发；一次整理无论提炼出多少条，这里只出声一次。
     *
     * @param titles   本次新建的正式待办标题（按入库顺序）
     * @param accepted 正式待办条数
     * @param pending  待确认条数（没有确定时间，不排提醒，但值得提一句）
     */
    public static void postBatch(Context ctx, List<String> titles, int accepted, int pending) {
        if ((titles == null || titles.isEmpty()) && pending <= 0) {
            return;
        }
        ensureBatchChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        StringBuilder body = new StringBuilder();
        int shown = 0;
        if (titles != null) {
            for (String t : titles) {
                if (t == null || t.trim().isEmpty()) {
                    continue;
                }
                if (shown >= BATCH_LINES) {
                    break;
                }
                if (shown > 0) {
                    body.append('\n');
                }
                body.append("· ").append(t.trim());
                shown++;
            }
            if (titles.size() > shown) {
                body.append('\n').append("…等 ").append(titles.size()).append(" 条");
            }
        }
        if (pending > 0) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("另有 ").append(pending).append(" 条待确认");
        }

        String title = accepted > 0
                ? "整理完成 · 新增 " + accepted + " 条"
                : "整理完成 · " + pending + " 条待确认";
        String text = body.length() == 0 ? "点开看看" : body.toString();

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(ctx, CHANNEL_BATCH)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_BATCH, notification);
    }
}
