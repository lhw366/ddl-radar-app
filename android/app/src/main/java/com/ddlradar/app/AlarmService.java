package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 提醒前台服务：系统闹钟的主备双通道直接以前台服务身份拉起本服务
 * （PendingIntent.getForegroundService，绕开 ColorOS 对后台应用的
 * 进程冻结与广播拦截——广播到冻结进程可能被扣下，前台服务拉起必须放行）。
 *
 * startForeground 直接挂提醒横幅（通道已静音，声音/震动由本服务直执行），
 * 播完自动退出前台。Android 8.0 以下无此 API，由 AlarmReceiver 委托 startService。
 */
public class AlarmService extends Service {

    private MediaPlayer player;
    private PowerManager.WakeLock wl;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int id = intent == null ? -1 : intent.getIntExtra("id", -1);
        String title = intent == null ? null : intent.getStringExtra("title");
        String body = intent == null ? null : intent.getStringExtra("body");
        String channelId = intent == null ? null : intent.getStringExtra("channelId");
        if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";
        long at = intent == null ? 0 : intent.getLongExtra("at", 0);
        long now = System.currentTimeMillis();

        // FGS 必须立刻挂通知：直接用提醒横幅本体（高优通道=屏幕横幅，通道静音不双响）
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification banner = buildBanner(id, title, body, channelId);
        if (banner != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Math.max(1, id), banner, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(Math.max(1, id), banner);
            }
        } else {
            startForeground(Math.max(1, id), minimalNotification());
        }

        // 主备双通道去重：同一提醒 60s 内只响一次
        long[] lf = AlarmScheduler.lastFire(this);
        boolean dup = lf[0] == id && now - lf[1] < 60000;
        if (id == -1 || dup) {
            if (!dup) AlarmScheduler.remove(this, Math.max(0, id));
            finish(startId);
            return START_NOT_STICKY;
        }
        AlarmScheduler.markFired(this, id, (at > 0 && now - at > 2500) ? "exact" : "clock");

        // 声音/震动直执行（按提醒方式：full 两者、vib 只震、ring 只响）
        acquireWakeLock();
        if (!"ddlr_vib".equals(channelId)) playChime();
        if (!"ddlr_ring".equals(channelId)) vibrate();

        AlarmScheduler.remove(this, id);
        finish(startId);
        return START_NOT_STICKY;
    }

    /** 提醒横幅（同时作为 FGS 通知）；通道缺失时兜底重建 */
    private Notification buildBanner(int id, String title, String body, String channelId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(channelId) == null) {
                channelId = "ddlr_full";
                if (nm.getNotificationChannel(channelId) == null) {
                    NotificationChannel ch = new NotificationChannel("ddlr_full",
                            "提醒 · 响铃+震动", NotificationManager.IMPORTANCE_HIGH);
                    ch.setShowBadge(true);
                    ch.enableVibration(false);
                    ch.setSound(null, null);
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, channelId);
            } else {
                b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);
            }
            PendingIntent pi = null;
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                pi = PendingIntent.getActivity(this, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            return b
                    .setContentTitle(title == null ? "DDL雷达" : title)
                    .setContentText(body == null ? "" : body)
                    .setSmallIcon(getApplicationInfo().icon)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private Notification minimalNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel("ddlr_svc") == null) {
                NotificationChannel ch = new NotificationChannel("ddlr_svc",
                        "提醒守护（静音）", NotificationManager.IMPORTANCE_MIN);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, "ddlr_svc");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        return b.setContentTitle("DDL雷达").setContentText("提醒服务运行中")
                .setSmallIcon(getApplicationInfo().icon).build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddlr:alarmSound");
                wl.acquire(9000);
            }
        } catch (Exception e) { /* ignore */ }
    }

    /** 铃声：闹钟音源（USAGE_ALARM），播完回调收尾 */
    private void playChime() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            player = MediaPlayer.create(this, R.raw.ddlr_chime, attrs, 1);
            if (player == null) return;
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) { m.release(); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    m.release();
                    return true;
                }
            });
            player.start();
        } catch (Exception e) {
            if (player != null) { try { player.release(); } catch (Exception ignore) {} }
        }
    }

    private void vibrate() {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
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

    /** 声音起播后延迟退出前台（给 MediaPlayer 起播留时间防进程秒杀截断铃声）；
     *  stopForeground(false) 把横幅留在通知栏由用户点按/划掉 */
    private void finish(final int startId) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    try { player.stop(); } catch (Exception ignore) {}
                }
                if (wl != null) { try { wl.release(); } catch (Exception ignore) {} }
                stopForeground(false);
                stopSelf(startId);
            }
        }, 6000);
    }
}
