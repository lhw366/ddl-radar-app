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
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 提醒守护前台服务——真闹钟 App 的核心保活设计。
 *
 * 为什么需要它：ColorOS 会在 App 切后台后冻结进程，把系统闹钟的投递扣下
 * （前台全绿、后台不响的根因）。闹钟 App 的解法是进程从启动起就常驻一个
 * 前台服务，让进程永不被冻结——进程活着，setAlarmClock 主备双通道的投递
 * 就和前台时完全一样，必然送达。
 *
 * 职责：
 * - 常驻低优先级通知「提醒守护中 · 下一条 HH:MM」（守护通道，静音）
 * - 闹钟到点（主备双通道 onStartCommand）在活进程内直响：留痕/去重/铃声/震动/
 *   横幅/清存储（声音震动直执行，不依赖通知权限与 ROM 展示规则）
 * - START_STICKY：被杀后系统自动重建；开机/覆盖安装由 BootReceiver 拉起
 *
 * 注意：状态栏的「提醒守护中」通知是保活机制的一部分，请勿在通知设置里关闭。
 */
public class AlarmService extends Service {

    static final String GUARD_CHANNEL = "ddlr_guard";
    static final int FG_ID = 19900214;

    private MediaPlayer player;
    private PowerManager.WakeLock wl;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, AlarmService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent != null && intent.getIntExtra("id", -1) != -1) {
            fireReminder(intent);
        } else {
            refreshGuard(); // 保活启动/告警列表变化：刷新「下一条」显示
        }
        return START_STICKY;
    }

    /* ---------------- 守护前台通知 ---------------- */

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(GUARD_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(GUARD_CHANNEL,
                    "提醒守护（静音常驻）", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = guardNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(FG_ID, n);
        }
    }

    private Notification guardNotification() {
        long nextAt = 0, now = System.currentTimeMillis();
        org.json.JSONArray arr = AlarmScheduler.load(this);
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("at", 0);
            if (at > now && (nextAt == 0 || at < nextAt)) nextAt = at;
        }
        String text = nextAt == 0 ? "暂无待提醒事项，添加后会自动守护"
                : "下一条：" + fmtShort(nextAt);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, GUARD_CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        PendingIntent pi = null;
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return b.setContentTitle("DDL雷达 · 提醒守护中")
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void refreshGuard() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(FG_ID, guardNotification());
        } catch (Exception e) { /* ignore */ }
    }

    /* ---------------- 闹钟到点：在活进程内直响 ---------------- */

    private void fireReminder(Intent intent) {
        int id = intent.getIntExtra("id", -1);
        long at = intent.getLongExtra("at", 0);
        long now = System.currentTimeMillis();

        // 主备双通道去重：同一提醒 60s 内只响一次（备份通道晚 3s 到达会被跳过）
        long[] lf = AlarmScheduler.lastFire(this);
        boolean dup = lf[0] == id && now - lf[1] < 60000;
        if (dup) {
            AlarmScheduler.remove(this, id);
            refreshGuard();
            return;
        }
        AlarmScheduler.markFired(this, id, (at > 0 && now - at > 2500) ? "exact" : "clock");

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String channelId = intent.getStringExtra("channelId");
        if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";

        // 声音/震动直执行（按提醒方式：full 两者、vib 只震、ring 只响）
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddlr:alarmSound");
                wl.acquire(9000);
            }
        } catch (Exception e) { /* ignore */ }
        if (!"ddlr_vib".equals(channelId)) playChime();
        if (!"ddlr_ring".equals(channelId)) vibrate();

        // 提醒横幅（高优通道，静音，锁屏可见）
        postBanner(id, title, body, channelId);

        AlarmScheduler.remove(this, id);
        refreshGuard();
    }

    private void postBanner(int id, String title, String body, String channelId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
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
            Notification n = b
                    .setContentTitle(title == null ? "DDL雷达" : title)
                    .setContentText(body == null ? "" : body)
                    .setSmallIcon(getApplicationInfo().icon)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi)
                    .build();
            nm.notify(id, n);
        } catch (Exception e) { /* 横幅失败不影响已执行的铃声/震动 */ }
    }

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

    private static String fmtShort(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        int m = c.get(java.util.Calendar.MONTH) + 1;
        int d = c.get(java.util.Calendar.DAY_OF_MONTH);
        int hh = c.get(java.util.Calendar.HOUR_OF_DAY);
        int mm = c.get(java.util.Calendar.MINUTE);
        return (m + "月" + d + "日 " + (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm);
    }

    @Override
    public void onDestroy() {
        if (player != null) { try { player.release(); } catch (Exception ignore) {} }
        if (wl != null) { try { wl.release(); } catch (Exception ignore) {} }
        super.onDestroy();
    }
}
