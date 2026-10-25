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
import com.getcapacitor.JSObject;

import org.json.JSONObject;

/** Shared, idempotent delivery for the direct service and legacy broadcasts. */
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
            boolean vibrateInSilent = item != null ? item.optBoolean("vibrateInSilent", true)
                    : intent.getBooleanExtra("vibrateInSilent", true);
            if (title == null || title.isEmpty()) title = "DDL雷达";
            if (body == null) body = "";
            if (channelId == null || channelId.isEmpty()) channelId = "ddlr_high_sound";
            ReminderTrace.mark(context, id, "delivery", "lateMs=" + (System.currentTimeMillis() - at)
                    + ";appVisible=" + MainActivity.visible);
            channelId = channelFor(channelId, vibrate);
            intent.putExtra("channelId", channelId);
            intent.putExtra("vibrateInSilent", vibrateInSilent);
            // Resolve effects from persisted content, even if it changed after
            // the PendingIntent was originally registered.
            intent.putExtra("title", title);
            intent.putExtra("body", body);
            boolean notified = post(context, id, title, body, channelId);
            String actualChannel = notificationChannelFor(context, channelId);
            boolean channelVibration = channelVibration(context, actualChannel);
            boolean notificationMayVibrate = channelVibration || soundMayBecomeVibration(context, actualChannel);
            // Outside silent-ringer mode, compatibility remains an explicit
            // choice encoded in the occurrence's channel. In silent mode the
            // separate, persisted vibrateInSilent preference owns haptics.
            boolean nativeMode = nativeVibration(channelId);
            boolean silent = ringerSilent(context);
            // System silent is distinct from the user's App-level silent switch.
            // A quiet notification never owns haptics; the finite service does.
            boolean direct = vibrate && (silent ? vibrateInSilent
                    : nativeMode && !(notified && notificationMayVibrate));
            intent.putExtra("directVibration", direct);
            String status = !vibrate ? "disabled" : silent && !vibrateInSilent ? "blocked_preference" : direct ? "native_pending"
                    : !notified ? "blocked_notification" : !notificationMayVibrate ? "blocked_channel" : "notification_requested";
            vibrationStatus(context, status);
            context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                    .putString("lastVibrationChannel", actualChannel).putString("lastVibrationUsage", "").apply();
            String stage = status.startsWith("blocked_") ? "vibration_blocked" : direct ? "vibration_pending"
                    : vibrate ? "vibration_requested" : "vibration_disabled";
            ReminderTrace.mark(context, id, stage, "status=" + status + ";channelVibration=" + channelVibration
                    + ";vibrateInSilent=" + vibrateInSilent + ";route="
                    + (direct ? "native_service" : nativeMode ? "notification_user_override" : "notification_channel"));
            context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                    .putLong("lastExpectedAt", at)
                    .putLong("lastDelayMs", Math.max(0, System.currentTimeMillis() - at)).apply();
            AlarmScheduler.recordDelivered(context, id, at);
            int slot = intent.getIntExtra("slot", 0);
            String mode = AlarmScheduler.lastSchedMode(context);
            AlarmScheduler.markFired(context, id,
                    AlarmService.ACTION_DUE.equals(intent.getAction()) ? "direct_service"
                            : slot == 3 ? "inexact" : "clock".equals(mode) ? "clock" : "exact");
            if (item != null) AlarmScheduler.completeOccurrence(context, item);
            return true;
        }
    }

    static boolean nativeVibration(String channel) {
        return channel != null && channel.contains("_v5_native_");
    }

    static boolean ringerSilent(Context context) {
        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audio != null && audio.getRingerMode() == android.media.AudioManager.RINGER_MODE_SILENT;
    }

    static boolean quietChannel(String channel) {
        return channel != null && channel.matches("ddlr_(high|gentle)_v6_quiet");
    }

    private static boolean channelVibration(Context context, String channel) {
        if (Build.VERSION.SDK_INT < 26) return channel.contains("_vib_");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel definition = manager == null ? null : manager.getNotificationChannel(channel);
        return definition != null && definition.shouldVibrate();
    }

    private static boolean soundMayBecomeVibration(Context context, String channel) {
        if (Build.VERSION.SDK_INT < 26) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel definition = manager == null ? null : manager.getNotificationChannel(channel);
        android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return definition != null && definition.getSound() != null && !Uri.EMPTY.equals(definition.getSound())
                && audio != null && audio.getRingerMode() == android.media.AudioManager.RINGER_MODE_VIBRATE
                && audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0;
    }

    static void vibrationStatus(Context context, String status) {
        context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                .putString("lastVibrationStatus", status).apply();
    }

    /** Finite native haptics for the selected route or a user-tapped self-check. */
    static JSObject requestNativeVibration(Context context, int id) {
        return requestNativeVibration(context, id, true);
    }

    static JSObject requestNativeVibration(Context context, int id, boolean vibrateInSilent) {
        JSObject result = new JSObject().put("requested", false).put("hasVibrator", false);
        context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                .putString("lastVibrationUsage", "").apply();
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            boolean available = vibrator != null && vibrator.hasVibrator();
            result.put("hasVibrator", available);
            if (!available) {
                vibrationStatus(context, "unavailable");
                ReminderTrace.mark(context, id, "vibration_unavailable", "hasVibrator=false");
                return result;
            }
            android.media.AudioManager audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            int filter = Build.VERSION.SDK_INT >= 23 && notifications != null
                    ? notifications.getCurrentInterruptionFilter() : NotificationManager.INTERRUPTION_FILTER_ALL;
            boolean silent = audio != null && audio.getRingerMode() == android.media.AudioManager.RINGER_MODE_SILENT;
            if (silent && !vibrateInSilent) {
                result.put("suppressed", "silent_vibration_disabled");
                vibrationStatus(context, "blocked_preference");
                ReminderTrace.mark(context, id, "vibration_blocked", "status=blocked_preference;reason=silent_vibration_disabled");
                return result;
            }
            // Never promote a notification past a DND filter. In priority mode
            // normal notification haptics remain subject to the OS's policy.
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE
                    || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
                    || (silent && filter != NotificationManager.INTERRUPTION_FILTER_ALL)) {
                result.put("suppressed", "do_not_disturb");
                vibrationStatus(context, "blocked_policy");
                ReminderTrace.mark(context, id, "vibration_blocked", "status=blocked_policy;reason=do_not_disturb");
                return result;
            }
            // This is a user's scheduled reminder, with an explicit silent
            // haptic preference. ALARM is used for its waveform only: no audio,
            // volume changes or bypass flags. NOTIFICATION would be discarded
            // by stock Android's silent-ringer gate before reaching the motor.
            int usage = silent ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION;
            String usageName = silent ? "alarm" : "notification";
            long[] pattern = new long[]{0, 350, 150, 350, 150, 650};
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes);
            else vibrator.vibrate(pattern, -1, attributes);
            result.put("requested", true);
            result.put("usage", usageName);
            context.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE).edit()
                    .putString("lastVibrationUsage", usageName).apply();
            vibrationStatus(context, "native_requested");
            ReminderTrace.mark(context, id, "direct_vibration_requested", "usage=" + usageName + ";foregroundService="
                    + AlarmService.isRunning() + ";appVisible=" + MainActivity.visible + ";hardwareConfirmed=false");
        } catch (Exception error) {
            result.put("error", error.toString());
            vibrationStatus(context, "error");
            ReminderTrace.mark(context, id, "vibration_error", error.toString());
        }
        return result;
    }

    /** Called after the finite dispatch service entered foreground. */
    static void vibrateFallback(Context context, int id, boolean requested) {
        vibrateFallback(context, id, requested, true);
    }

    static void vibrateFallback(Context context, int id, boolean requested, boolean vibrateInSilent) {
        if (!requested) return;
        if (!AlarmService.isRunning()) {
            vibrationStatus(context, "error");
            ReminderTrace.mark(context, id, "vibration_error", "dispatch_service_not_foreground");
            return;
        }
        requestNativeVibration(context, id, vibrateInSilent);
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
        if (nativeVibration(id)) name = banner ? "原生兼容提醒 · 横幅" : "原生兼容提醒 · 通知";
        else if (quietChannel(id)) name = banner ? "系统静音时 · 横幅" : "系统静音时 · 通知";
        else name += id.contains("_vib_") ? " · 震动" : " · 无震动";
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
        Uri cue = !nativeVibration(id) && (sound || banner) ? Uri.parse("android.resource://" + context.getPackageName()
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
        if (nativeVibration(base))
            return "ddlr_" + (banner ? "high" : "gentle") + "_v5_native_" + (sound ? "sound" : "silent");
        return "ddlr_" + (banner ? "high" : "gentle") + "_v4_"
                + (vibrate ? "vib_" : "still_") + (sound ? "sound" : "silent");
    }

    static String normalizeChannelId(String id) {
        if (quietChannel(id)) return id;
        if (id != null && id.matches("ddlr_(high|gentle)_v4_(vib|still)_(sound|silent)")) return id;
        if (id != null && id.matches("ddlr_(high|gentle)_v5_native_(sound|silent)")) return id;
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
        // Permission is not proof that an OEM will display a background window.
        // Keep the system heads-up as an independent fallback outside the App.
        // Only a visible App may use the quieter duplicate notification route.
        String display = MainActivity.visible && channelId.startsWith("ddlr_high_")
                && ReminderBanner.allowed(context) && !screenOff(context)
                ? channelId.replace("ddlr_high_", "ddlr_gentle_") : channelId;
        return backgroundNotificationChannelFor(context, display);
    }

    static String backgroundNotificationChannelFor(Context context, String display) {
        if (ringerSilent(context)) return "ddlr_" + (display.startsWith("ddlr_high_") ? "high" : "gentle") + "_v6_quiet";
        return display;
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
        boolean autoDismiss = context.getSharedPreferences("ddlr_display", Context.MODE_PRIVATE)
                .getBoolean("autoDismiss", false);
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
            page.putExtra("autoDismiss", autoDismiss);
            builder.setFullScreenIntent(PendingIntent.getActivity(context, notificationId(id), page,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), true);
        }
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
                .putBoolean("lastNotificationAllowed", allowed)
                .putString("lastDisplayChannel", channelId)
                .putBoolean("lastDeliveryAppVisible", MainActivity.visible).apply();
        ReminderTrace.mark(context, id, "notification", "allowed=" + allowed + ";channel=" + channelId + ";vibration=" + (Build.VERSION.SDK_INT < 26 ? channelId.contains("_vib_") : manager.getNotificationChannel(channelId).shouldVibrate()));
        return allowed;
    }

}
