package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/** The web layer resolves this existing reminder hash to its current task or course. */
final class ReminderNavigation {
    private ReminderNavigation() { }

    static Intent intent(Context context, int notificationId) {
        return new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("ddlr://reminder/" + (notificationId & Integer.MAX_VALUE)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static boolean open(Context context, int notificationId) {
        try {
            context.startActivity(intent(context, notificationId));
            ReminderTrace.mark(context, notificationId & Integer.MAX_VALUE, "reminder_open", "source=card");
            return true;
        } catch (Exception error) {
            Log.w("DdlAlarm", "Cannot open reminder", error);
            ReminderTrace.mark(context, notificationId & Integer.MAX_VALUE, "reminder_open_error", error.toString());
            return false;
        }
    }
}
