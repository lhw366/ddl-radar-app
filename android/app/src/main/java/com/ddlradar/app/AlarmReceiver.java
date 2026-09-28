package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 系统闹钟触发接收器：由 AlarmManager 的主备双通道唤醒
 * （主：setAlarmClock，备：setExactAndAllowWhileIdle +3s，60s 窗口去重）。
 *
 * 声音与震动由接收器直接执行（USAGE_ALARM 铃声 + 系统震动服务），
 * 不依赖通知权限、通道声音或 ROM 的通知展示规则——保证「一定有动静」；
 * 通知横幅为纯视觉补充（通道已静音），权限允许时投递。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        if (id == -1) return;
        long now = System.currentTimeMillis();

        // 主备双通道去重：同一提醒 60s 内只响一次（备份通道晚 3s 到达会被跳过）
        long[] lf = AlarmScheduler.lastFire(context);
        if (lf[0] == id && now - lf[1] < 60000) {
            AlarmScheduler.remove(context, id);
            return;
        }
        long at = intent.getLongExtra("at", 0);
        AlarmScheduler.markFired(context, id,
                (at > 0 && now - at > 2500) ? "exact" : "clock");

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String channelId = intent.getStringExtra("channelId");
        if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";

        // 铃声/震动无条件直执行（按提醒方式：full 两者、vib 只震、ring 只响）
        if (!"ddlr_vib".equals(channelId)) playChime(context);
        if (!"ddlr_ring".equals(channelId)) vibrate(context);

        // 通知横幅（纯视觉）：通知权限允许时投递；通道被清理则兜底重建（静音通道）
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean canNotify = nm != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || nm.areNotificationsEnabled());
        if (canNotify) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(channelId) == null) {
                channelId = "ddlr_full";
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(defaultChannel(context));
                }
            }
            try {
                Notification.Builder b;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    b = new Notification.Builder(context, channelId);
                } else {
                    b = new Notification.Builder(context).setPriority(Notification.PRIORITY_MAX);
                }

                PendingIntent pi = null;
                Intent open = context.getPackageManager()
                        .getLaunchIntentForPackage(context.getPackageName());
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
                        .setWhen(now)
                        .setContentIntent(pi)
                        .build();
                nm.notify(id, n);
            } catch (Exception e) { /* 横幅失败不影响已执行的铃声/震动 */ }
        }

        // 触发完成：从持久化列表移除，保持「已排定」诊断数真实
        AlarmScheduler.remove(context, id);
    }

    /** 铃声：闹钟音源（USAGE_ALARM），自持短 WakeLock 保证播完 */
    private void playChime(Context context) {
        MediaPlayer mp = null;
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddlr:alarmSound");
                wl.acquire(5000);
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mp = MediaPlayer.create(context, R.raw.ddlr_chime, attrs, 1);
            if (mp == null) return;
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) { m.release(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    m.release();
                    return true;
                }
            });
            mp.start();
        } catch (Exception e) {
            if (mp != null) { try { mp.release(); } catch (Exception ignore) {} }
        }
    }

    /** 震动：由系统震动服务执行，进程死掉也完整走完波形 */
    private void vibrate(Context context) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 200, 120, 200, 120, 400};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception e) { /* ignore */ }
    }

    /** 兜底重建的默认通道：静音（声音/震动由接收器直执行，通道只承载横幅） */
    private NotificationChannel defaultChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(
                "ddlr_full", "提醒 · 响铃+震动", NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(false);
        ch.setSound(null, null);
        return ch;
    }
}
