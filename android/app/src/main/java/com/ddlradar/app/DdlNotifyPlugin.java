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
        call.resolve(new JSObject().put("granted", granted));
    }

    /** 打开系统应用详情页（ColorOS 后台保活引导：自启动/耗电管理在此页内） */
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
            String id = call.getString("id", "ddlr_full");
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
