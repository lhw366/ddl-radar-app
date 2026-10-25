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
    static final String ACTION_DUE = "com.ddlradar.app.DUE_SERVICE";
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

    private static synchronized void acquireWake(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (power != null) {
            if (handoffWake == null) {
                handoffWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DDLRadar:handoff");
                handoffWake.setReferenceCounted(false);
            }
            handoffWake.acquire(12000L); // Crash/failure ceiling, actively released below.
        }
    }

    static boolean dispatch(Context context, Intent source) {
        try {
            acquireWake(context);
            Intent delivery = new Intent(source).setClass(context, AlarmService.class)
                    .putExtra("postedByReceiver", true);
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
            boolean directEntry = ACTION_DUE.equals(intent.getAction());
            if (directEntry) AlarmArrival.record(this, intent, "foreground_service");
            acquireWake(this);
            ReminderTrace.mark(this, lastId, "service", "foreground=true;on_demand=true;route="
                    + (directEntry ? "foreground_service" : "legacy_handoff"));
            boolean delivered = false;
            try {
                // A legacy receiver already posted and resolved its extras.
                // Direct alarm starts must claim delivery once before any effects.
                delivered = !directEntry && intent.getBooleanExtra("postedByReceiver", false)
                        && AlarmScheduler.wasDelivered(this, lastId, intent.getLongExtra("at", 0));
                if (!delivered) delivered = AlarmDelivery.deliver(this, intent);
            } catch (Exception error) {
                Log.e("DdlAlarm", "Reminder delivery failed", error);
                ReminderTrace.mark(this, lastId, "delivery_error", error.toString());
            }
            if (!delivered) {
                ReminderTrace.mark(this, lastId, "delivery_skipped", "duplicate_or_invalid");
                finishWhenIdle();
                return START_NOT_STICKY;
            }
            // Add the native window only after startForeground succeeded.
            // Background overlays must not depend on a paused WebView or on
            // the short-lived broadcast receiver keeping the process alive.
            String channel = intent.getStringExtra("channelId");
            if (channel != null && channel.startsWith("ddlr_high_")) {
                boolean autoDismiss = getSharedPreferences("ddlr_display", MODE_PRIVATE)
                        .getBoolean("autoDismiss", false);
                ReminderBanner.show(this, AlarmDelivery.notificationId(lastId),
                        intent.getStringExtra("title"), intent.getStringExtra("body"), autoDismiss);
            }
            // Keep foreground priority through the short, finite vibration.
            AlarmDelivery.vibrateFallback(this, lastId, intent.getBooleanExtra("directVibration", false),
                    intent.getBooleanExtra("vibrateInSilent", true));
            ReminderSound.play(this, lastId, intent.getStringExtra("channelId"));
            long hold = ReminderSound.canPlay(this, channel) ? 4000L
                    : intent.getBooleanExtra("directVibration", false) ? 2200L : 0L;
            effectsUntil = Math.max(effectsUntil, SystemClock.uptimeMillis() + hold);
        }
        finishWhenIdle();
        return START_NOT_STICKY; // No permanent guard or process-resurrection loop.
    }

    private void finishWhenIdle() {
        handler.removeCallbacks(finishEffects);
        handler.postDelayed(finishEffects, Math.max(1L, effectsUntil - SystemClock.uptimeMillis()));
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
