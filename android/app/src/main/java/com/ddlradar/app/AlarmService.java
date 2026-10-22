package com.ddlradar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/** Optional foreground status; reminder delivery is owned by AlarmReceiver. */
public class AlarmService extends Service {
    static final String GUARD_CHANNEL = "ddlr_guard";
    static final int FG_ID = 19900214;
    private static volatile boolean running;
    private boolean foregrounded;
    private String lastGuardText;
    private PendingIntent cachedContentPi;

    static boolean isRunning() { return running; }

    static void start(Context context) {
        Intent intent = new Intent(context, AlarmService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception error) {
            // A failed guard start cannot invalidate a successful alarm schedule.
            Log.w("DdlAlarm", "Reminder status service unavailable", error);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        goForeground();
    }

    @Override public void onDestroy() {
        running = false;
        foregrounded = false;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregrounded) goForeground();
        // Handle an already dispatched v5 PendingIntent during an app upgrade.
        if (intent != null && intent.hasExtra("id")) {
            try { AlarmDelivery.deliver(this, intent); }
            catch (Exception error) { Log.e("DdlAlarm", "Legacy reminder delivery failed", error); }
        }
        if (intent != null && intent.hasExtra("ackId")) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(intent.getIntExtra("ackId", 0));
        }
        if (!foregrounded) { stopSelf(); return START_NOT_STICKY; }
        refreshGuard();
        return START_STICKY;
    }

    private void goForeground() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && manager.getNotificationChannel(GUARD_CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(GUARD_CHANNEL,
                        "提醒守护（静音常驻）", NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            Notification notification = guardNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else startForeground(FG_ID, notification);
            foregrounded = true;
            running = true;
        } catch (Exception error) {
            running = false;
            foregrounded = false;
            Log.w("DdlAlarm", "Cannot enter foreground", error);
            stopSelf();
        }
    }

    private Notification guardNotification() {
        long nextAt = 0, now = System.currentTimeMillis();
        org.json.JSONArray items = AlarmScheduler.load(this);
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            long at = item.optLong("at", 0);
            if (at > now && (nextAt == 0 || at < nextAt)) nextAt = at;
        }
        String text = nextAt == 0 ? "暂无待提醒事项，添加后会自动守护" : "下一条：" + fmtShort(nextAt);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, GUARD_CHANNEL)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        if (cachedContentPi == null) {
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) cachedContentPi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return builder.setContentTitle("DDL雷达 · 提醒守护中")
                .setContentText(text).setSmallIcon(R.drawable.ic_stat_ddlr)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(cachedContentPi).build();
    }

    private void refreshGuard() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            Notification notification = guardNotification();
            String text = notification.extras.getString(Notification.EXTRA_TEXT);
            if (text != null && text.equals(lastGuardText)) return;
            manager.notify(FG_ID, notification);
            lastGuardText = text;
        } catch (Exception error) { Log.w("DdlAlarm", "Cannot refresh reminder status", error); }
    }

    static int channelImportance(NotificationManager manager, String id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_HIGH;
        if (manager == null) return -1;
        NotificationChannel channel = manager.getNotificationChannel(id);
        return channel == null ? -1 : channel.getImportance();
    }

    private static String fmtShort(long timestamp) {
        return new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(timestamp));
    }
}