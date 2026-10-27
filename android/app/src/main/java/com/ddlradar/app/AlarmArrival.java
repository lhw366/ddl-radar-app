package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Records entry before delivery work, distinguishing service starts from broadcasts. */
final class AlarmArrival {
    static void record(Context context, Intent intent, String route) {
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        long foregroundAgo = MainActivity.lastForegroundElapsed == 0 ? -1 : elapsed - MainActivity.lastForegroundElapsed;
        long backgroundAgo = MainActivity.lastBackgroundElapsed == 0 ? -1 : elapsed - MainActivity.lastBackgroundElapsed;
        int id = intent.getIntExtra("id", -1);
        long expectedAt = intent.getLongExtra("at", 0);
        context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                .putLong("lastEntryAt", now).putLong("lastEntryId", id).putString("lastEntryRoute", route)
                .putLong("lastEntryExpectedAt", expectedAt).putLong("lastEntryDelayMs", now - expectedAt)
                .putLong("lastEntrySinceForegroundMs", foregroundAgo)
                .putLong("lastEntrySinceBackgroundMs", backgroundAgo).apply();
        ReminderTrace.mark(context, id, "alarm_entry", "route=" + route + ";appVisible=" + MainActivity.visible
                + ";lateMs=" + (now - expectedAt) + ";expectedAt=" + expectedAt
                + ";sinceForegroundMs=" + foregroundAgo + ";sinceBackgroundMs=" + backgroundAgo);
    }
    private AlarmArrival() { }
}
