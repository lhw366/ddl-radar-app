package com.ddlradar.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL雷达 通知通道助手。
 * 插件自带的 createChannel 不支持 vibration（源码里无 enableVibration 调用），
 * 这里用原生 API 正确创建带震动/铃声的通道，并提供通知权限状态与申请。
 * （调度职责已移交 DdlAlarm：setAlarmClock 免精确闹钟授权，无需相关跳转）
 */
@CapacitorPlugin(name = "DdlNotify", permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class DdlNotifyPlugin extends Plugin {

    @PluginMethod
    public void testVibration(PluginCall call) {
        if (!MainActivity.visible) {
            call.reject("请保持应用在前台进行原生震动自检");
            return;
        }
        call.resolve(AlarmDelivery.requestNativeVibration(getContext(), 990000002,
                call.getBoolean("vibrateInSilent", true)));
    }

    @PluginMethod
    public void notifyUpdate(PluginCall call) {
        int code = call.getInt("code", 0);
        Context ctx = getContext();
        if (code <= 0 || !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            call.resolve(); return;
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("ddlr_updates", 0);
        if (prefs.getInt("announced", 0) >= code) { call.resolve(); return; }
        try {
            NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            String channel = "ddlr_updates";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel definition = new NotificationChannel(channel, "版本更新", NotificationManager.IMPORTANCE_DEFAULT);
                definition.setSound(null, null);
                definition.enableVibration(false);
                manager.createNotificationChannel(definition);
            }
            android.content.Intent open = new android.content.Intent(ctx, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent content = android.app.PendingIntent.getActivity(ctx, 19900215, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder notification = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(ctx, channel) : new android.app.Notification.Builder(ctx);
            manager.notify(19900215, notification.setSmallIcon(R.drawable.ic_stat_ddlr)
                    .setContentTitle("DDL雷达有新版本 " + call.getString("version", ""))
                    .setContentText("点击打开应用，确认后更新 · b" + code)
                    .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).build());
            prefs.edit().putInt("announced", code).apply();
            call.resolve();
        } catch (Exception error) { call.reject("更新通知不可用", error); }
    }

    @PluginMethod
    public void copyDiagnostics(PluginCall call) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DDL雷达提醒诊断", call.getString("text", "")));
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception error) { call.reject("无法复制诊断: " + error.getMessage()); }
    }

    @PluginMethod
    public void configureDisplay(PluginCall call) {
        getContext().getSharedPreferences("ddlr_display", Context.MODE_PRIVATE).edit()
                .putBoolean("autoDismiss", "5s".equals(call.getString("bannerDuration", "manual"))).apply();
        call.resolve(new JSObject().put("overlayAllowed", ReminderBanner.allowed(getContext())));
    }

    @PluginMethod
    public void openBannerPermission(PluginCall call) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            call.resolve(new JSObject().put("opened", true));
        } catch (Exception error) { call.reject("无法打开悬浮窗设置: " + error.getMessage()); }
    }

    @PluginMethod
    public void ensureChannel(PluginCall call) {
        String id = call.getString("id");
        String name = call.getString("name");
        Integer importance = call.getInt("importance");
        Boolean vibration = call.getBoolean("vibration");
        String sound = call.getString("sound");
        Boolean force = call.getBoolean("force", false);

        if (id == null || name == null || importance == null) {
            call.reject("id/name/importance required");
            return;
        }
        try {
            Context ctx = getContext();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                call.resolve(new JSObject().put("ok", true).put("reused", true));
                return;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) { call.reject("no notification manager"); return; }
            if (id.startsWith("ddlr_high_") || id.startsWith("ddlr_gentle_")) {
                // One native definition for JS initialization, boot recovery,
                // and actual delivery. Do not delete/recreate the same ID:
                // Android restores its former settings, including blocks.
                String actualId = AlarmDelivery.ensureChannel(ctx, nm, id);
                call.resolve(new JSObject().put("ok", true).put("id", actualId));
                return;
            }

            // 通道一经创建，重要性/声音/震动就归用户所有，系统不允许 App 改写。
            // 早前这里「先删后建」强制生效，代价是：每删一次，用户在系统设置里
            // 对该通道的调整（静音、改成其他铃声、关闭横幅）全部作废，
            // 而且删通道会让已排定的提醒失去通道、到点静默。
            // 现在：通道已存在就复用，不删除；只有用户主动点「修复」才重建。
            NotificationChannel existing = nm.getNotificationChannel(id);
            if (existing != null && Boolean.TRUE.equals(force)) {
                nm.deleteNotificationChannel(id);
                existing = null;
            }
            if (existing != null) {
                JSObject ret = new JSObject().put("ok", true).put("reused", true);
                call.resolve(ret);
                return;
            }
            NotificationChannel ch = new NotificationChannel(id, name, importance);
            ch.setShowBadge(true);
            ch.setLockscreenVisibility(1); // VISIBILITY_PUBLIC

            if (vibration != null && vibration) {
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 200, 120, 200});
            } else {
                ch.enableVibration(false);
            }

            if (sound != null && !sound.isEmpty()) {
                int dot = sound.lastIndexOf('.');
                Uri soundUri = Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/" +
                        (dot > 0 ? sound.substring(0, dot) : sound));
                // USAGE_NOTIFICATION：走通知/铃声音量流，音量键可直接调节。
                // 早前用 USAGE_ALARM（闹钟音量流），音量键控制不了，
                // 这是「提示音大小不能用音量键控制」的根因。
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                ch.setSound(soundUri, attrs);
            } else {
                ch.setSound(null, null);
            }

            nm.createNotificationChannel(ch);
            JSObject ret = new JSObject().put("ok", true).put("reused", false);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("ensureChannel failed: " + e.getMessage());
        }
    }

    /** 通知是否可用（系统级真相，含 Android 13+ 运行时权限与用户手动关闭） */
    @PluginMethod
    public void notifyStatus(PluginCall call) {
        boolean granted = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        JSObject ret = new JSObject().put("granted", granted)
                .put("overlayAllowed", ReminderBanner.allowed(getContext()));
        // 锁屏/灭屏亮屏：Android 14+ 是独立的用户开关，关掉后全屏 Intent 会被折叠
        boolean fullScreen = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try { fullScreen = nm.canUseFullScreenIntent(); } catch (Exception error) { fullScreen = true; }
            }
        }
        ret.put("canFullScreen", fullScreen);
        call.resolve(ret);
    }

    /** 直达「锁屏亮屏提醒 / 全屏通知」开关（Android 14+）；旧版本退化为应用详情页 */
    @PluginMethod
    public void openFullScreenSettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                call.resolve(new JSObject().put("opened", true));
                return;
            }
        } catch (Exception error) {
            // 个别 ROM 没有这个页面：落到下面的应用详情
        }
        openAppDetails(call);
    }

    /** Open per-app notification controls using the public Settings intent. */
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                call.resolve(new JSObject().put("opened", true));
                return;
            }
        } catch (Exception error) { /* Some OEMs only expose the app info page. */ }
        openAppDetails(call);
    }

    /** Open app info; restricted settings, when offered, require user confirmation here. */
    @PluginMethod
    public void openAppDetails(PluginCall call) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve(new JSObject().put("opened", true));
        } catch (Exception e) {
            call.reject("open failed: " + e.getMessage());
        }
    }

    /**
     * 直达「本应用的某个通知通道」设置页。
     *
     * 为什么需要：通道的重要性/声音/震动一经创建就归用户所有，App 改不了。
     * 如果用户曾把它调成「静音」或降低重要性，提醒就不会弹横幅——
     * 此时唯一的修法是引导用户到该通道的设置页手动调回来。
     * Android 8+ 用 ACTION_CHANNEL_NOTIFICATION_SETTINGS 可精确跳转。
     */
    @PluginMethod
    public void openChannelSettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                openAppDetails(call);
                return;
            }
            String id = call.getString("id", "ddlr_high_sound");
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            i.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, id);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve(new JSObject().put("opened", true));
        } catch (Exception e) {
            // 个别 ROM 不支持直达通道页，退化为应用详情页
            try {
                openAppDetails(call);
            } catch (Exception e2) {
                call.reject("open channel settings failed: " + e.getMessage());
            }
        }
    }

    @PluginMethod
    public void listChannelIds(PluginCall call) {
        try {
            NotificationManager nm = (NotificationManager) getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            JSObject ret = new JSObject();
            List<String> ids = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                for (NotificationChannel ch : nm.getNotificationChannels()) ids.add(ch.getId());
            }
            ret.put("ids", ids);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("list failed: " + e.getMessage());
        }
    }
}
