package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * 系统闹钟触发接收器：由 AlarmManager.setAlarmClock() 唤醒。
 * setAlarmClock 是系统时钟应用专用 API：免精确闹钟授权、
 * Doze 深度休眠下保证准时、退后台/杀进程/重启均保证触发。
 * 声音与震动由通知通道携带（DdlNotify.ensureChannel 按提醒方式创建），
 * 接收器只负责把通知投递到对应通道。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        if (id == -1) return;
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String channelId = intent.getStringExtra("channelId");
        if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 通道被系统/清数据清理时兜底重建默认通道，保证提醒能弹出
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(channelId) == null) {
            channelId = "ddlr_full";
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(defaultChannel(context));
            }
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(context, channelId);
        } else {
            b = new Notification.Builder(context)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }

        PendingIntent pi = null;
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            pi = PendingIntent.getActivity(context, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        Notification n = b
                .setContentTitle(title == null ? "DDL雷达" : title)
                .setContentText(body == null ? "" : body)
                .setSmallIcon(context.getApplicationInfo().icon)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi)
                .build();

        nm.notify(id, n);
        // 触发完成：从持久化列表移除，保持「已排定」诊断数真实
        AlarmScheduler.remove(context, id);
    }

    private NotificationChannel defaultChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(
                "ddlr_full", "提醒 · 响铃+震动", NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 200, 120, 200});
        try {
            Uri sound = Uri.parse("android.resource://" + context.getPackageName()
                    + "/raw/ddlr_chime");
            ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        } catch (Exception e) {
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);
        }
        return ch;
    }
}
