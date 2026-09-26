package com.ddlradar.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * DDL雷达 通知通道与精确闹钟助手。
 * 插件自带的 createChannel 不支持 vibration（源码里无 enableVibration 调用），
 * 这里用原生 API 正确创建带震动/铃声的通道，并提供精确闹钟状态与跳转。
 */
@CapacitorPlugin(name = "DdlNotify")
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
            ch.setLockscreenVisibility(1); // VISIBILITY_PRIVATE

            if (vibration != null && vibration) {
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 200, 120, 200});
            } else {
                ch.enableVibration(false);
            }

            if (sound != null && !sound.isEmpty()) {
                Uri soundUri = Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/" +
                        sound.substring(0, sound.lastIndexOf('.')));
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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

    @PluginMethod
    public void exactStatus(PluginCall call) {
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager am = (android.app.AlarmManager) getContext()
                    .getSystemService(Context.ALARM_SERVICE);
            granted = am != null && am.canScheduleExactAlarms();
        }
        call.resolve(new JSObject().put("granted", granted));
    }

    @PluginMethod
    public void openExactSettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(i);
                call.resolve(new JSObject().put("opened", true));
            } else {
                call.resolve(new JSObject().put("opened", false));
            }
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
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (NotificationChannel ch : nm.getNotificationChannels()) {
                ids.add(ch.getId());
            }
            ret.put("ids", ids);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("list failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void batteryStatus(PluginCall call) {
        android.os.PowerManager pm = (android.os.PowerManager) getContext()
                .getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        JSObject ret = new JSObject()
                .put("ignoring", ignoring)
                .put("sdk", Build.VERSION.SDK_INT);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestIgnoreBattery(PluginCall call) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getContext()
                    .getSystemService(Context.POWER_SERVICE);
            String pkg = getContext().getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
            call.resolve(new JSObject().put("done", true));
        } catch (Exception e) {
            call.reject("request failed: " + e.getMessage());
        }
    }
}
