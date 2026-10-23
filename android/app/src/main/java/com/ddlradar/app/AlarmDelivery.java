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
    private static final String GROUP = "com.ddlradar.app.reminders";
    private static final int SUMMARY_ID = 19900216;
    private AlarmDelivery() {}

    static boolean deliver(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        long at = intent.getLongExtra("at", 0);
        if (id < 0 || at <= 0) return false;
        synchronized (AlarmScheduler.class) {
            JSONObject item = AlarmScheduler.findOccurrence(context, id, at);
            if (AlarmScheduler.wasDelivered(context, id, at)) return false;
            // 少数 ROM 会提前几毫秒派发。早前用严格 now 比较，正好卡在窗口里时
            // 会直接 return false —— 一次真实的提醒就这么被静默丢弃且无人重试。
            if (at > System.currentTimeMillis() + 2000L) return false;
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
            ReminderTrace.mark(context, id, "delivery", "lateMs=" + (System.currentTimeMillis() - at)
                    + ";appVisible=" + MainActivity.visible);
            channelId = channelFor(channelId, vibrate);
            intent.putExtra("channelId", channelId);
            boolean notified = post(context, id, title, body, channelId);
            // NotificationManager owns vibration while the application is in
            // the background. Never duplicate it with a direct Vibrator call.
            boolean direct = vibrate && !notified;
            intent.putExtra("directVibration", direct);
            ReminderTrace.mark(context, id, "vibration_requested",
                    "enabled=" + vibrate + ";route=" + (direct ? "foreground_fallback" : "notification_channel"));
            context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                    .putLong("lastExpectedAt", at)
                    .putLong("lastDelayMs", Math.max(0, System.currentTimeMillis() - at)).apply();
            AlarmScheduler.recordDelivered(context, id, at);
            int slot = intent.getIntExtra("slot", 0);
            String mode = AlarmScheduler.lastSchedMode(context);
            AlarmScheduler.markFired(context, id,
                    slot == 2 ? "service" : "clock".equals(mode) ? "clock" : "exact");
            if (item != null) AlarmScheduler.completeOccurrence(context, item);
            return true;
        }
    }

    /** Last-resort vibration after the dispatch service entered foreground. */
    static void vibrateFallback(Context context, int id, boolean requested) {
        if (!requested) return;
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                ReminderTrace.mark(context, id, "vibration_unavailable", "hasVibrator=false"); return;
            }
            long[] pattern = new long[]{0, 350, 150, 350, 150, 650};
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes);
            } else {
                vibrator.vibrate(pattern, -1, attributes);
            }
            ReminderTrace.mark(context, id, "direct_vibration_requested", "usage=alarm;after_foreground_start");
        } catch (Exception error) {
            Log.w("DdlAlarm", "Reminder vibration unavailable", error);
            ReminderTrace.mark(context, id, "vibration_error", error.toString());
        }
    }

    /**
     * 屏幕是否已熄灭。
     *
     * 只认「灭屏」，不认「锁屏」：亮着屏的锁屏界面本来就能显示 heads-up 通知，
     * 此时再抢一层全屏页面纯属打扰。灭屏才是「用户根本看不见通知」的场景，
     * 也是系统闹钟亮屏提醒的语义所在。
     */
    private static boolean screenOff(Context context) {
        try {
            android.os.PowerManager power =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && !power.isInteractive();
        } catch (Exception error) {
            return false;
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
        name += id.contains("_vib_") ? " · 震动" : " · 无震动";
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // Immutable channels encode the independent vibration switch.
        boolean vibration = id.contains("_vib_");
        channel.enableVibration(vibration);
        if (vibration) channel.setVibrationPattern(new long[]{0, 350, 150, 350, 150, 650});
        // Some ROMs suppress heads-up for channels with neither sound nor
        // channel vibration. A silent PCM cue keeps banner-only reminders
        // alerting without adding audible sound or changing direct vibration.
        Uri cue = (sound || banner) ? Uri.parse("android.resource://" + context.getPackageName()
                + "/raw/ddlr_silence") : null;
        channel.setSound(cue, cue == null ? null : new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(channel);
        return id;
    }

    static String channelFor(String id, boolean vibrate) {
        String base = id == null ? "ddlr_high_sound" : id;
        boolean banner = base.startsWith("ddlr_high_") || "ddlr_vib".equals(base);
        boolean sound = !base.endsWith("_silent") && !"ddlr_vib".equals(base);
        return "ddlr_" + (banner ? "high" : "gentle") + "_v4_"
                + (vibrate ? "vib_" : "still_") + (sound ? "sound" : "silent");
    }

    static String normalizeChannelId(String id) {
        if (id != null && id.matches("ddlr_(high|gentle)_v4_(vib|still)_(sound|silent)")) return id;
        return channelFor(id, true);
    }

    static int notificationId(int id) {
        // Negative ids are separate from the positive foreground notification id.
        return id | Integer.MIN_VALUE;
    }

    static void cancelNotification(Context context, int id) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 24) {
            boolean other = false;
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                if (GROUP.equals(active.getNotification().getGroup()) && active.getId() != id
                        && active.getId() != SUMMARY_ID) { other = true; break; }
            }
            if (!other) manager.cancel(SUMMARY_ID);
        }
        manager.cancel(id);
    }

    private static void postSummary(Context context, NotificationManager manager, boolean autoDismiss) {
        if (Build.VERSION.SDK_INT < 24) return;
        String channel = ensureChannel(context, manager, "ddlr_gentle_v4_still_silent");
        Notification.Builder summary = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        summary.setSmallIcon(R.drawable.ic_stat_ddlr).setContentTitle("DDL雷达 · 提醒")
                .setContentText("展开查看待处理提醒").setGroup(GROUP).setGroupSummary(true)
                .setOnlyAlertOnce(true).setAutoCancel(true).setDefaults(0);
        if (Build.VERSION.SDK_INT >= 26) {
            summary.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
            if (autoDismiss) summary.setTimeoutAfter(5200L);
        }
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) summary.setContentIntent(activity(context, SUMMARY_ID, "summary", open));
        manager.notify(SUMMARY_ID, summary.build());
    }

    static String notificationChannelFor(Context context, String channelId) {
        // SystemUI heads-up windows cover application overlays. With a visible
        // custom banner, keep native vibration but omit the duplicate heads-up.
        return channelId.startsWith("ddlr_high_") && ReminderBanner.allowed(context) && !screenOff(context)
                ? channelId.replace("ddlr_high_", "ddlr_gentle_") : channelId;
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

    private static boolean post(Context context, int id, String title, String body, String channelId) {
        boolean banner = channelId.startsWith("ddlr_high_");
        channelId = notificationChannelFor(context, channelId);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("Notification manager unavailable");
        channelId = ensureChannel(context, manager, channelId);
        boolean allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || manager.getNotificationChannel(channelId).getImportance() != NotificationManager.IMPORTANCE_NONE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channelId);
        } else {
            builder = new Notification.Builder(context);
            builder.setDefaults(0);
            if (channelId.contains("_vib_")) builder.setVibrate(new long[]{0, 350, 150, 350, 150, 650});
        }
        // A finite native service cue owns audible sound; channels never double it.
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
        // 灭屏时的「看得见」：挂全屏 Intent 让提醒页越过锁屏亮屏。
        // 亮屏使用中绝不这样做（那是顶部横幅该做的事）。
        // AlarmActivity 早前是纯死代码——manifest 里声明了、注释写着靠
        // fullScreenIntent 拉起，但没有任何地方真的设置过它，所以灭屏/锁屏
        // 从来没有亮屏提醒过。这里补上真正的调用点。
        if (banner && screenOff(context)) {
            Intent page = new Intent(context, AlarmActivity.class);
            page.setData(Uri.parse("ddlr://alarm-screen/" + id));
            page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            page.putExtra("title", title);
            page.putExtra("body", body);
            page.putExtra("notifId", notificationId(id));
            builder.setFullScreenIntent(PendingIntent.getActivity(context, notificationId(id), page,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), true);
        }
        boolean autoDismiss = context.getSharedPreferences("ddlr_display", Context.MODE_PRIVATE)
                .getBoolean("autoDismiss", false);
        if (Build.VERSION.SDK_INT >= 24) builder.setGroup(GROUP);
        if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
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
        try {
            // Automatic grouping on Android 16 can mark new children SILENT.
            // Own the group explicitly; the summary never replaces child alerts.
            postSummary(context, manager, autoDismiss);
            manager.notify(notificationId(id), builder.build());
        }
        catch (SecurityException error) {
            // Notification permission and overlay permission are independent.
            allowed = false;
            Log.w("DdlAlarm", "System notification blocked", error);
        }
        context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                .putBoolean("lastNotificationAllowed", allowed).apply();
        ReminderTrace.mark(context, id, "notification", "allowed=" + allowed + ";channel=" + channelId + ";vibration=" + (Build.VERSION.SDK_INT < 26 ? channelId.contains("_vib_") : manager.getNotificationChannel(channelId).shouldVibrate()));
        if (banner) {
            ReminderBanner.show(context, notificationId(id), title, body, autoDismiss);
        }
        return allowed;
    }

}
