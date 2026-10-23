package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Runs only while delivering an event or showing a user-retained banner. No polling. */
public class AlarmService extends Service {
    static final String GUARD_CHANNEL = "ddlr_guard";
    static final int FG_ID = 19900214;
    private static volatile AlarmService active;
    private static PowerManager.WakeLock handoffWake;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long effectsUntil;
    private int lastId;
    private int lastStartId;
    private final Runnable finishEffects = () -> {
        releaseWake();
        ReminderTrace.mark(this, lastId, "wake_released", "handoff complete");
        if (ReminderBanner.visibleCount() == 0) stopSelfResult(lastStartId);
    };

    static boolean isRunning() { return active != null; }
    static synchronized boolean isWakeHeld() { return handoffWake != null && handoffWake.isHeld(); }

    static synchronized void releaseWake() {
        if (handoffWake != null && handoffWake.isHeld()) handoffWake.release();
    }

    static boolean dispatch(Context context, Intent source) {
        try {
            PowerManager power = (PowerManager) context.getSystemService(POWER_SERVICE);
            if (power != null) {
                synchronized (AlarmService.class) {
                    if (handoffWake == null) {
                        handoffWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DDLRadar:handoff");
                        handoffWake.setReferenceCounted(false);
                    }
                    handoffWake.acquire(12000L); // Crash/failure ceiling, actively released below.
                }
            }
            Intent delivery = new Intent(source).setClass(context, AlarmService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(delivery);
            else context.startService(delivery);
            return true;
        } catch (Exception error) {
            releaseWake();
            ReminderTrace.mark(context, source.getIntExtra("id", -1), "service_start_failed", error.toString());
            return false;
        }
    }

    static void bannerChanged() {
        AlarmService owner = active;
        if (owner == null) return;
        owner.handler.removeCallbacks(owner.finishEffects);
        long remaining = owner.effectsUntil - SystemClock.uptimeMillis();
        if (remaining > 0) owner.handler.postDelayed(owner.finishEffects, remaining);
        else owner.finishEffects.run();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) {
            NotificationChannel channel = new NotificationChannel(GUARD_CHANNEL,
                    "提醒显示服务", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, GUARD_CHANNEL)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        Notification notification = builder.setSmallIcon(R.drawable.ic_stat_ddlr)
                .setContentTitle("DDL雷达 · 正在显示提醒")
                .setContentText("提醒完成且顶部横幅关闭后自动结束")
                .setOngoing(true).setOnlyAlertOnce(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(FG_ID, notification);
        active = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent != null && intent.hasExtra("id")) {
            lastId = intent.getIntExtra("id", -1);
            ReminderTrace.mark(this, lastId, "service", "foreground=true;on_demand=true");
            // 正常路径下接收器已经同步投递过，这里 deliver() 会因 wasDelivered 直接返回；
            // 保留这次调用是为了覆盖「服务被系统/测试单独拉起」的场景。
            try { AlarmDelivery.deliver(this, intent); }
            catch (Exception error) { Log.e("DdlAlarm", "Reminder delivery failed", error); }
            // Keep foreground priority through the short, finite vibration.
            AlarmDelivery.vibrateFallback(this, lastId, intent.getBooleanExtra("directVibration", false));
            ReminderSound.play(this, lastId, intent.getStringExtra("channelId"));
            String channel = intent.getStringExtra("channelId");
            effectsUntil = SystemClock.uptimeMillis() + (channel != null && channel.endsWith("_sound") ? 4000L : 2200L);
        }
        handler.removeCallbacks(finishEffects);
        handler.postDelayed(finishEffects, Math.max(1L, effectsUntil - SystemClock.uptimeMillis()));
        return START_NOT_STICKY; // No permanent guard or process-resurrection loop.
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        active = null;
        releaseWake();
        ReminderSound.stop();
        ReminderBanner.dismissAll();
        stopForeground(true);
        super.onDestroy();
    }

    static int channelImportance(NotificationManager manager, String id) {
        if (Build.VERSION.SDK_INT < 26) return NotificationManager.IMPORTANCE_HIGH;
        if (manager == null) return -1;
        NotificationChannel channel = manager.getNotificationChannel(id);
        return channel == null ? -1 : channel.getImportance();
    }
}
