package dev.campus.todo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Reminder.post(ctx,
                intent.getStringExtra(Reminder.EXTRA_TITLE),
                intent.getStringExtra(Reminder.EXTRA_BODY));
    }
}
