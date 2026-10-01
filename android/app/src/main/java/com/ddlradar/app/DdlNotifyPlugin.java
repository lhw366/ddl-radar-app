package com.ddlradar.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

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

        if (id == null || name == null || importance == null) {
            call.reject("id/name/importance required");
            return;
        }
        try {
            Context ctx = getContext();
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) { call.reject("no notification manager"); return; }

            // 通道一经创建设置不可改：先删旧通道再重建，保证本次设置生效
            if (nm.getNotificationChannel(id) != null) {
                nm.deleteNotificationChannel(id);
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
                Uri soundUri = Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/" +
                        sound.substring(0, sound.lastIndexOf('.')));
                // USAGE_ALARM：走闹钟音量、免勿扰干扰，与系统闹钟同语义
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                ch.setSound(soundUri, attrs);
            } else {
                ch.setSound(null, null);
            }

            nm.createNotificationChannel(ch);
            JSObject ret = new JSObject().put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("ensureChannel failed: " + e.getMessage());
        }
    }

    /** 通知是否可用（系统级真相，含 Android 13+ 运行时权限与用户手动关闭） */
    @PluginMethod
    public void notifyStatus(PluginCall call) {
        NotificationManager nm = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        boolean granted = nm != null && nm.areNotificationsEnabled();
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

    @PluginMethod
    public void listChannelIds(PluginCall call) {
        try {
            NotificationManager nm = (NotificationManager) getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            JSObject ret = new JSObject();
            List<String> ids = new ArrayList<>();
            for (NotificationChannel ch : nm.getNotificationChannels()) {
                ids.add(ch.getId());
            }
            ret.put("ids", ids);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("list failed: " + e.getMessage());
        }
    }
}
