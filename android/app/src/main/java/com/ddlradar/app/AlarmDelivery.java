package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import org.json.JSONObject;

/** Short, synchronous delivery while AlarmManager holds the receiver's wake lock. */
final class AlarmDelivery {
    private AlarmDelivery() {}

    static boolean deliver(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        long at = intent.getLongExtra("at", 0);
        if (id < 0 || at <= 0) return false;
        synchronized (AlarmScheduler.class) {
            // Check the persisted occurrence. A delayed backup must not repeat,
            // or consume a new date for the same reminder id.
            JSONObject item = AlarmScheduler.findOccurrence(context, id, at);
            if (item == null || AlarmScheduler.wasDelivered(context, id, at)) return false;
            if (at > System.currentTimeMillis() + 1000) return false;
            String channelId = item.optString("channel", "ddlr_full");
            vibrateForMode(context, channelId);
            post(context, id, item.optString("title", "DDL雷达"),
                    item.optString("body", ""), channelId);
            AlarmScheduler.recordDelivered(context, id, at);
            AlarmScheduler.markFired(context, id,
                    intent.getIntExtra("slot", 0) == 1 ? "exact" : "clock");
            AlarmScheduler.completeOccurrence(context, item);
            return true;
        }
    }

    /**
     * Vibration is executed by the receiver itself. Notification-channel
     * vibration is user-editable and several ColorOS builds silently ignore
     * the channel pattern while allowing direct vibration from an exact alarm.
     */
    private static void vibrateForMode(Context context, String channelId) {
        if ("ddlr_ring".equals(channelId)) return;
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = new long[]{0, 350, 150, 350, 150, 650};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception error) {
            Log.w("DdlAlarm", "Reminder vibration unavailable", error);
        }
    }

    static String ensureChannel(Context context, NotificationManager manager, String id) {
        if (!"ddlr_vib".equals(id) && !"ddlr_ring".equals(id)) id = "ddlr_full";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id;
        if (manager.getNotificationChannel(id) != null) return id;
        boolean sound = !"ddlr_vib".equals(id);
        boolean vibration = !"ddlr_ring".equals(id);
        String name = !sound ? "提醒 · 仅震动" : !vibration ? "提醒 · 仅响铃" : "提醒 · 响铃+震动";
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        if (vibration) channel.setVibrationPattern(new long[]{0, 200, 120, 200});
        channel.enableVibration(vibration);
        channel.setSound(sound ? Uri.parse("android.resource://" + context.getPackageName() + "/raw/ddlr_chime") : null,
                sound ? new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build() : null);
        manager.createNotificationChannel(channel);
        return id;
    }

    static int notificationId(int id) {
        // Negative ids are separate from the positive foreground notification id.
        return id | Integer.MIN_VALUE;
    }

    private static PendingIntent activity(Context context, int id, String purpose, Intent intent) {
        intent.setData(Uri.parse("ddlr://notification/" + id + "/" + purpose));
        return PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action action(Context context, String title, PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_ddlr),
                    title, intent).build();
        }
        return new Notification.Action.Builder(R.drawable.ic_stat_ddlr, title, intent).build();
    }

    private static void post(Context context, int id, String title, String body, String channelId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("Notification manager unavailable");
        channelId = ensureChannel(context, manager, channelId);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channelId);
        } else {
            builder = new Notification.Builder(context);
            int defaults = "ddlr_vib".equals(channelId) ? Notification.DEFAULT_VIBRATE
                    : "ddlr_ring".equals(channelId) ? Notification.DEFAULT_SOUND
                    : Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE;
            builder.setDefaults(defaults);
        }
        // System-owned sound survives process suspension and avoids double sound
        // from a notification channel and an additional MediaPlayer.
        builder.setSmallIcon(R.drawable.ic_stat_ddlr)
                .setContentTitle(title.isEmpty() ? "DDL雷达" : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent content = activity(context, id, "open", open);
            builder.setContentIntent(content);
            builder.addAction(action(context, "去看看", content));
        }
        Intent alarm = new Intent(context, AlarmActivity.class);
        alarm.putExtra("title", title);
        alarm.putExtra("body", body);
        alarm.putExtra("notifId", notificationId(id));
        alarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // A foreground service does not grant background Activity launch rights.
        if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(activity(context, id, "fullscreen", alarm), true);
        }
        Intent ack = new Intent(context, AlarmReceiver.class);
        ack.setAction("com.ddlradar.app.ACK_REMINDER");
        ack.setData(Uri.parse("ddlr://notification/" + id + "/ack"));
        ack.putExtra("ackId", notificationId(id));
        PendingIntent acknowledge = PendingIntent.getBroadcast(context, id, ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(action(context, "知道了", acknowledge));
        manager.notify(notificationId(id), builder.build());
        launchFullScreenFallback(context, manager, alarm);
    }

    private static void launchFullScreenFallback(Context context, NotificationManager manager, Intent alarm) {
        try {
            if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) return;
            android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean screenOff = power != null && !power.isInteractive();
            boolean locked = keyguard != null && keyguard.isKeyguardLocked();
            if (!screenOff && !locked) return;
            alarm.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(alarm);
        } catch (Exception error) {
            // The notification's full-screen PendingIntent remains the system-approved route.
            Log.w("DdlAlarm", "Direct full-screen launch unavailable", error);
        }
    }
}
