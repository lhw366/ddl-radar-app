package com.ddlradar.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Notifications are posted before attempting any optional foreground service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (intent.getIntExtra("slot", 0) == 2) {
            long at = intent.getLongExtra("at", 0);
            // A stale/cancelled prewake must not keep the device awake.
            if (AlarmScheduler.findOccurrence(context, intent.getIntExtra("id", -1), at) != null
                    && at > System.currentTimeMillis() && at <= System.currentTimeMillis() + 10000) {
                AlarmService.prepareDeadline(context, at);
            }
            return;
        }
        if (intent.hasExtra("ackId")) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(intent.getIntExtra("ackId", 0));
            ReminderBanner.dismiss(intent.getIntExtra("ackId", 0));
            return;
        }
        AlarmScheduler.markReceiverHit(context, intent.getIntExtra("id", 0),
                intent.getIntExtra("slot", 0) == 1 ? "exact" : "clock");
        try {
            AlarmDelivery.deliver(context, intent);
        } catch (Exception error) {
            // Leave the occurrence pending for the backup to retry.
            Log.e("DdlAlarm", "Reminder delivery failed", error);
        }
        AlarmService.start(context);
    }
}
