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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 提醒守护前台服务——真闹钟 App 的核心保活设计。
 *
 * 为什么需要它：ColorOS 会在 App 切后台后冻结进程，把系统闹钟的投递扣下。
 * 进程常驻前台服务 → 不被冻结 → setAlarmClock 主备双通道的投递和前台一样必然送达。
 *
 * 到点路径极简（系统通知优先，系统代劳一切表现）：
 * 只把通知投递到对应通道——铃声/震动由通道携带（系统在通知送达时播放，
 * 不依赖本进程存活），HIGH 重要性由系统弹顶部横幅，锁屏由全屏 Intent 亮屏。
 * 本服务不做任何自定义声震/界面渲染。
 *
 * 守护通知「提醒守护中 · 下一条 HH:MM」是保活机制的一部分，请勿关闭。
 */
public class AlarmService extends Service {

    static final String GUARD_CHANNEL = "ddlr_guard";
    static final int FG_ID = 19900214;

    private static volatile boolean running;

    static boolean isRunning() { return running; }

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
        running = true;
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
        // 类型必须与 manifest 的 foregroundServiceType 一致（specialUse）。
        // 早前这里传 MEDIA_PLAYBACK 而 manifest 写 mediaPlayback，Android 14+
        // 会因「类型与声明不符」或「无媒体播放行为」判定非法，startForeground
        // 抛异常 → 服务未进入前台 → 被系统回收 → 后台投递丢失。
        // 另外 startForeground 会抛多种运行时异常（FGS 类型不支持、被系统限制
        // 启动前台服务等），绝不能让异常冒泡——那会直接崩掉服务，连 onStartCommand
        // 里后面的 fireReminder 都执行不到，表现就是「到点什么都不响」。
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse 常量本身是 API 34 引入的，低于 34 传入会被系统判为非法类型
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FG_ID, n);
            }
        } catch (Exception e) {
            // 退化：不带类型再试一次；仍失败则仅保留普通通知，至少提醒逻辑继续跑
            try {
                startForeground(FG_ID, n);
            } catch (Exception ignore) {
                try { if (nm != null) nm.notify(FG_ID, n); } catch (Exception ignore2) {}
            }
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

    /* ---------------- 闹钟到点：交给系统通知 ---------------- */

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

        // 自播兜底：通道铃声依赖「通知权限 + 通道未被用户静音」两个外部条件，
        // 任一不满足就完全无声。系统闹钟从不依赖通知权限，这里同样直执行声震，
        // 保证即使通知权限被关，锁屏/后台到点也一定听得见（通道仍在时会有双响，
        // 可接受：宁可响两下，不可一声不响）。
        playSoundAndVibrate(channelId);

        postBanner(id, title, body, channelId);

        AlarmScheduler.remove(this, id);
        refreshGuard();
    }

    /* ---------------- 声震直执行（不依赖通知权限） ---------------- */

    private void playSoundAndVibrate(String channelId) {
        boolean wantSound = !"ddlr_vib".equals(channelId);
        boolean wantVibrate = !"ddlr_ring".equals(channelId);
        if (wantSound) playChime();
        if (wantVibrate) vibrate();
    }

    private void playChime() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            final MediaPlayer player = MediaPlayer.create(this, R.raw.ddlr_chime, attrs, 1);
            if (player == null) return;
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { try { m.release(); } catch (Exception ignore) {} }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    try { m.release(); } catch (Exception ignore) {}
                    return true;
                }
            });
            player.start();
        } catch (Exception e) { /* 铃声失败不阻断通知投递 */ }
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
                    ch.enableVibration(true);
                    ch.setVibrationPattern(new long[]{0, 200, 120, 200});
                    ch.setSound(android.net.Uri.parse("android.resource://com.ddlradar.app/raw/ddlr_chime"),
                            new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build());
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, channelId);
            } else {
                b = new Notification.Builder(this)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
            }
            PendingIntent pi = null;
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                pi = PendingIntent.getActivity(this, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            // 闹钟式全屏提醒：锁屏/灭屏直接亮屏进入 AlarmActivity（系统闹钟同款）
            try {
                Intent alarm = new Intent(this, AlarmActivity.class);
                alarm.putExtra("title", title);
                alarm.putExtra("body", body);
                PendingIntent fsi = PendingIntent.getActivity(this, 1000000 + id, alarm,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.setFullScreenIntent(fsi, true);
            } catch (Exception ignore) { /* 全屏 Intent 不可用时退化为普通横幅 */ }
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
        } catch (Exception e) { /* 投递失败不影响其他提醒 */ }
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
}
