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
            JSONObject item = AlarmScheduler.findOccurrence(context, id, at);
            if (AlarmScheduler.wasDelivered(context, id, at)) return false;
            if (at > System.currentTimeMillis()) return false;
            // Persisted state can be reconciled a few milliseconds before the
            // alarm fires. The PendingIntent extras are the last known good
            // copy, so deliver them instead of silently dropping a real alarm.
            String title = item != null ? item.optString("title", "DDL雷达")
                    : intent.getStringExtra("title");
            String body = item != null ? item.optString("body", "")
                    : intent.getStringExtra("body");
            String channelId = item != null ? item.optString("channel", "ddlr_high_sound")
                    : intent.getStringExtra("channelId");
            boolean vibrate = item != null ? item.optBoolean("vibrate", true)
                    : intent.getBooleanExtra("vibrate", true);
            if (title == null || title.isEmpty()) title = "DDL雷达";
            if (body == null) body = "";
            if (channelId == null || channelId.isEmpty()) channelId = "ddlr_high_sound";
            vibrateForMode(context, channelId, vibrate);
            post(context, id, title, body, channelId);
            context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                    .putLong("lastExpectedAt", at)
                    .putLong("lastDelayMs", Math.max(0, System.currentTimeMillis() - at)).apply();
            AlarmScheduler.recordDelivered(context, id, at);
            int slot = intent.getIntExtra("slot", 0);
            AlarmScheduler.markFired(context, id,
                    slot == 1 ? "exact" : slot == 2 ? "service" : "clock");
            if (item != null) AlarmScheduler.completeOccurrence(context, item);
            return true;
        }
    }

    /**
     * Vibration is executed by the receiver itself. Notification-channel
     * vibration is user-editable and several ColorOS builds silently ignore
     * the channel pattern while allowing direct vibration from an exact alarm.
     */
    private static void vibrateForMode(Context context, String channelId, boolean requested) {
        if (!requested) return;
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
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes);
            } else {
                vibrator.vibrate(pattern, -1, attributes);
            }
        } catch (Exception error) {
            Log.w("DdlAlarm", "Reminder vibration unavailable", error);
        }
    }

    static String ensureChannel(Context context, NotificationManager manager, String id) {
        id = normalizeChannelId(id);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id;
        if (manager.getNotificationChannel(id) != null) return id;
        boolean sound = id.endsWith("_sound");
        boolean banner = id.startsWith("ddlr_high_");
        int importance = banner ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;
        String name = banner ? (sound ? "提醒 · 横幅+响铃" : "提醒 · 横幅静音")
                : (sound ? "提醒 · 通知+响铃" : "提醒 · 静音通知");
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // Vibration is executed by AlarmReceiver so it remains independent
        // from the sound and banner channel settings.
        channel.enableVibration(false);
        // Some ROMs suppress heads-up for channels with neither sound nor
        // channel vibration. A silent PCM cue keeps banner-only reminders
        // alerting without adding audible sound or changing direct vibration.
        Uri cue = (sound || banner) ? Uri.parse("android.resource://" + context.getPackageName()
                + (sound ? "/raw/ddlr_chime" : "/raw/ddlr_silence")) : null;
        channel.setSound(cue, cue == null ? null : new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(channel);
        return id;
    }

    static String normalizeChannelId(String id) {
        if (id != null && (id.equals("ddlr_high_v2_sound") || id.equals("ddlr_high_v2_silent")
                || id.equals("ddlr_gentle_v2_sound") || id.equals("ddlr_gentle_v2_silent"))) return id;
        if (id != null && (id.equals("ddlr_high_sound") || id.equals("ddlr_high_silent")
                || id.equals("ddlr_gentle_sound") || id.equals("ddlr_gentle_silent"))) {
            return id.replace("_sound", "_v2_sound").replace("_silent", "_v2_silent");
        }
        if ("ddlr_vib".equals(id)) return "ddlr_high_v2_silent";
        return "ddlr_high_v2_sound";
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
        boolean allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || manager.getNotificationChannel(channelId).getImportance() != NotificationManager.IMPORTANCE_NONE);
        context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                .putBoolean("lastNotificationAllowed", allowed).apply();
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channelId);
        } else {
            builder = new Notification.Builder(context);
            int defaults = channelId.endsWith("_sound") ? Notification.DEFAULT_SOUND : 0;
            builder.setDefaults(defaults);
        }
        // System-owned sound survives process suspension and avoids double sound
        // from a notification channel and an additional MediaPlayer.
        builder.setSmallIcon(R.drawable.ic_stat_ddlr)
                .setContentTitle(title.isEmpty() ? "DDL雷达" : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(channelId.startsWith("ddlr_high_")
                        ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());
        boolean autoDismiss = context.getSharedPreferences("ddlr_display", Context.MODE_PRIVATE)
                .getBoolean("autoDismiss", false);
        if (autoDismiss && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(5000L);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent content = activity(context, id, "open", open);
            builder.setContentIntent(content);
            builder.addAction(action(context, "去看看", content));
        }
        Intent ack = new Intent(context, AlarmReceiver.class);
        ack.setAction("com.ddlradar.app.ACK_REMINDER");
        ack.setData(Uri.parse("ddlr://notification/" + id + "/ack"));
        ack.putExtra("ackId", notificationId(id));
        PendingIntent acknowledge = PendingIntent.getBroadcast(context, id, ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(action(context, "知道了", acknowledge));
        try { manager.notify(notificationId(id), builder.build()); }
        catch (SecurityException error) {
            // Notification permission and overlay permission are independent.
            Log.w("DdlAlarm", "System notification blocked", error);
        }
        if (channelId.startsWith("ddlr_high_")) {
            ReminderBanner.show(context, notificationId(id), title, body, autoDismiss);
        }
    }

}
