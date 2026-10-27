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
import org.json.JSONArray;
import org.json.JSONObject;
import com.getcapacitor.JSObject;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite near-deadline protection and reminder effects. No polling or waiting CPU lock. */
public class AlarmService extends Service {
    static final String ACTION_DUE = "com.ddlradar.app.DUE_SERVICE";
    static final String ACTION_PREPARE = "com.ddlradar.app.PREPARE_NEAR_REMINDER";
    static final long MAX_PROTECTION_MS = 180000L;
    static final String GUARD_CHANNEL = "ddlr_guard";
    static final int FG_ID = 19900214;
    private static volatile AlarmService active;
    private static PowerManager.WakeLock handoffWake;
    private static final ConcurrentLinkedQueue<CountDownLatch> prepareAcks = new ConcurrentLinkedQueue<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long effectsUntil;
    private int lastId;
    private int lastStartId;
    private volatile Intent protectedIntent;
    private volatile long protectionExpiresElapsed;
    private final Runnable protectedDeadline = this::runProtectedDeadline;
    private final Runnable expireProtection = () -> {
        Intent waiting = protectedIntent;
        clearProtection();
        if (waiting != null) ReminderTrace.mark(this, waiting.getIntExtra("id", -1), "protection_expired", "limitMs=" + MAX_PROTECTION_MS);
        finishWhenIdle();
    };
    private final Runnable finishEffects = () -> {
        releaseWake();
        ReminderTrace.mark(this, lastId, "wake_released", "handoff complete");
        if (ReminderBanner.visibleCount() == 0 && protectedIntent == null) stopSelfResult(lastStartId);
    };

    static boolean isRunning() { return active != null; }
    static synchronized boolean isWakeHeld() { return handoffWake != null && handoffWake.isHeld(); }

    static JSObject protectionState() {
        AlarmService owner = active;
        Intent waiting = owner == null ? null : owner.protectedIntent;
        return new JSObject().put("waiting", waiting != null)
                .put("expectedAt", waiting == null ? 0 : waiting.getLongExtra("at", 0))
                .put("expiresElapsed", owner == null ? 0 : owner.protectionExpiresElapsed)
                .put("maxWaitMs", MAX_PROTECTION_MS);
    }

    /** Called after an acknowledged native save, cancel, or while leaving the UI.
     * Far-future schedules never start an idle service. Existing protection is
     * reconciled on the main queue, including removal or edits of its occurrence.
     */
    static void planChanged(Context context) {
        CountDownLatch ready = new CountDownLatch(1);
        AlarmService owner = active;
        if (owner != null) {
            owner.handler.post(() -> {
                try { owner.updateProtection(true); owner.finishWhenIdle(); }
                finally { ready.countDown(); }
            });
        } else {
            if (!MainActivity.visible || nextNear(context, System.currentTimeMillis() + MAX_PROTECTION_MS) == null) return;
            prepareAcks.add(ready);
            try {
                Intent prepare = new Intent(context, AlarmService.class).setAction(ACTION_PREPARE);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(prepare);
                else context.startService(prepare);
            } catch (Exception error) {
                prepareAcks.remove(ready);
                ready.countDown();
                ReminderTrace.mark(context, -1, "protection_start_failed", error.getClass().getName());
            }
        }
        // Capacitor's worker acknowledges actual preparation before the save/test
        // success returns. Activity lifecycle callbacks never wait on the UI.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                if (!ready.await(2, TimeUnit.SECONDS)) ReminderTrace.mark(context, -1, "protection_ack_timeout", "");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { prepareAcks.remove(ready); }
        }
    }

    private static JSONObject nextNear(Context context, long latestAt) {
        synchronized (AlarmScheduler.class) {
            JSONArray items = AlarmScheduler.load(context);
            JSONObject first = null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                long id = item.optLong("id", -1), at = item.optLong("at", 0);
                if (at < now - 2000 || at > latestAt || !item.optBoolean("scheduled", true)
                        || !AlarmScheduler.isRegistered(context, id, at) || AlarmScheduler.wasDelivered(context, id, at)) continue;
                if (first == null || at < first.optLong("at")) first = item;
            }
            return first;
        }
    }

    private void clearProtection() {
        handler.removeCallbacks(protectedDeadline);
        handler.removeCallbacks(expireProtection);
        protectedIntent = null;
        protectionExpiresElapsed = 0;
    }

    private void updateProtection(boolean mayStartWindow) {
        long elapsed = SystemClock.elapsedRealtime(), now = System.currentTimeMillis();
        if (protectionExpiresElapsed <= elapsed) {
            clearProtection();
            if (!mayStartWindow) return;
            protectionExpiresElapsed = elapsed + MAX_PROTECTION_MS;
        }
        JSONObject next = nextNear(this, now + (protectionExpiresElapsed - elapsed));
        if (next == null) { clearProtection(); return; }
        int id = next.optInt("id");
        long at = next.optLong("at");
        Intent before = protectedIntent;
        protectedIntent = new Intent(this, AlarmService.class).setAction(ACTION_DUE)
                .putExtra("id", id).putExtra("at", at).putExtra("protectedTimer", true);
        handler.removeCallbacks(protectedDeadline);
        handler.removeCallbacks(expireProtection);
        handler.postDelayed(protectedDeadline, Math.max(1L, at - now));
        handler.postDelayed(expireProtection, Math.max(1L, protectionExpiresElapsed - elapsed + 1000L));
        if (before == null || before.getIntExtra("id", -1) != id || before.getLongExtra("at", 0) != at)
            ReminderTrace.mark(this, id, "protection_started", "expectedAt=" + at
                    + ";remainingMs=" + (at - now) + ";limitMs=" + MAX_PROTECTION_MS);
    }

    private void runProtectedDeadline() {
        Intent due = protectedIntent;
        if (due == null) return;
        long at = due.getLongExtra("at", 0);
        if (at > System.currentTimeMillis()) {
            updateProtection(false); // A wall-clock change may move the deadline.
            finishWhenIdle();
            return;
        }
        protectedIntent = null;
        handler.removeCallbacks(expireProtection);
        handleReminder(due, true);
    }

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
                .setContentTitle("DDL雷达 · 临近提醒")
                .setContentText("到点提醒或横幅关闭后自动结束，等待时不保持 CPU 唤醒")
                .setOngoing(true).setOnlyAlertOnce(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(FG_ID, notification);
        active = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent != null && ACTION_PREPARE.equals(intent.getAction())) {
            updateProtection(true);
            finishWhenIdle();
            CountDownLatch ack;
            while ((ack = prepareAcks.poll()) != null) ack.countDown();
            return START_NOT_STICKY;
        }
        handleReminder(intent, false);
        return START_NOT_STICKY;
    }

    private void handleReminder(Intent intent, boolean fromProtection) {
        if (intent != null && intent.hasExtra("id")) {
            lastId = intent.getIntExtra("id", -1);
            boolean directEntry = ACTION_DUE.equals(intent.getAction());
            if (directEntry) AlarmArrival.record(this, intent, fromProtection ? "protected_timer" : "foreground_service");
            acquireWake(this);
            ReminderTrace.mark(this, lastId, "service", "foreground=true;on_demand=true;route="
                    + (fromProtection ? "protected_timer" : directEntry ? "foreground_service" : "legacy_handoff"));
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
                boolean early = intent.getLongExtra("at", 0) > System.currentTimeMillis() + 2000L
                        && AlarmScheduler.isRegistered(this, lastId, intent.getLongExtra("at", 0));
                updateProtection(early);
                finishWhenIdle();
                return;
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
            // A real delivery can hand off to the next near occurrence. This
            // matters when a late gradient leaves its deadline only minutes
            // away. Duplicate/early callbacks never renew this window.
            clearProtection();
            updateProtection(true);
        }
        finishWhenIdle();
    }

    private void finishWhenIdle() {
        handler.removeCallbacks(finishEffects);
        handler.postDelayed(finishEffects, Math.max(1L, effectsUntil - SystemClock.uptimeMillis()));
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        active = null;
        clearProtection();
        CountDownLatch ack;
        while ((ack = prepareAcks.poll()) != null) ack.countDown();
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
