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
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 系统闹钟触发接收器：由 AlarmManager.setAlarmClock() 唤醒。
 * setAlarmClock 是系统时钟应用专用 API：免精确闹钟授权、
 * Doze 深度休眠下保证准时、退后台/杀进程/重启均保证触发。
 *
 * 声音与震动由通知通道携带（DdlNotify.ensureChannel 按提醒方式创建）；
 * 若通知会被系统静默丢弃（通知权限被拒 / 通道被关），改为接收器内
 * 直接播放铃声 + 震动兜底（真实闹钟行为），保证「一定有动静」。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        if (id == -1) return;
        // 触发留痕最先写：证明广播确实到达（诊断「闹钟没触发」vs「通知没弹出」）
        AlarmScheduler.markFired(context, id);

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String channelId = intent.getStringExtra("channelId");
        if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        boolean blocked;
        if (nm == null) {
            blocked = true;
        } else {
            // 通道被系统/清数据清理时兜底重建默认通道，保证提醒能弹出
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(channelId) == null) {
                channelId = "ddlr_full";
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(defaultChannel(context));
                }
            }
            blocked = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                blocked = !nm.areNotificationsEnabled();
            }
            if (!blocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = nm.getNotificationChannel(channelId);
                blocked = ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
            }
        }

        // 通知会被系统静默丢弃 → 直接响铃/震动（按提醒方式：full 两者、vib 只震、ring 只响）
        if (blocked) {
            if (!"ddlr_vib".equals(channelId)) playChime(context);
            if (!"ddlr_ring".equals(channelId)) vibrate(context);
            AlarmScheduler.remove(context, id);
            return;
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

    /** 兜底铃声：闹钟音源（USAGE_ALARM），自持短 WakeLock 保证播完 */
    private void playChime(Context context) {
        MediaPlayer mp = null;
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddlr:alarmFallback");
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

    /** 兜底震动：由系统震动服务执行，进程死掉也完整走完波形 */
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
