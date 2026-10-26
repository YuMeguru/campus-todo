package dev.campus.todo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 重启后闹钟会丢，这里按库里的待办重新排一遍。 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Reminder.ensureChannel(ctx);
        int count = 0;
        for (Model.Todo todo : Db.get(ctx).activeWithDue()) {
            Reminder.schedule(ctx, todo);
            count++;
        }
        Log.i(TAG, "开机后重排提醒 " + count + " 条");
    }
}
