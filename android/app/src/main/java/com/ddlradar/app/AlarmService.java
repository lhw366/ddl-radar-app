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
    /**
     * 提醒通知 id 的偏移量。
     *
     * 提醒 id 来自 JS 的 hashId()，范围 0~2147483646；而守护通知固定用 FG_ID
     * （19900214）。两者落在同一命名空间里，一旦某条提醒的 hash 恰好等于
     * FG_ID，就会和守护通知互相覆盖：要么守护通知被提醒顶掉（保活失效），
     * 要么提醒被守护通知顶掉（用户收不到这条提醒）。
     * 加一个足够大的偏移把两个区段彻底隔开——这是「有时收不到某条提醒」
     * 的一个隐蔽成因（概率低但一旦命中就是静默丢失）。
     */
    static final int NOTIF_ID_OFFSET = 1000000000;

    /** 把 JS 侧提醒 id 映射成不会与守护通知冲突的通知 id */
    static int notifId(long alarmId) {
        return (int) ((alarmId & 0x3FFFFFFF) + NOTIF_ID_OFFSET);
    }

    private static volatile boolean running;
    /** 是否已成功进入前台：避免每次 onStartCommand 重复 startForeground */
    private volatile boolean foregrounded;
    /** 上一次守护通知的文案：内容没变就不再 notify，避免无谓唤醒与耗电 */
    private static volatile String lastGuardText;
    /** 缓存的内容 PendingIntent：每次重建会不断唤醒系统服务 */
    private PendingIntent cachedContentPi;

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

    /**
     * 服务销毁时必须复位标志。
     * 早前没有 onDestroy：running 一旦置 true 就永远是 true，
     * 诊断面板的「守护服务运行中」会一直显示绿色 —— 用户以为保活生效，
     * 实际服务早就被系统杀了，这正是「切后台不响却查不出原因」的陷阱。
     */
    @Override
    public void onDestroy() {
        running = false;
        foregrounded = false;
        lastGuardText = null;
        cachedContentPi = null;
        releasePlayer(); // 释放正在播放的铃声，避免泄漏与残留发声
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 注意：这里**不能**直接 running = true。
        // running 的含义是「守护服务已成功进入前台」，是诊断面板判断保活
        // 是否生效的依据。若在 startForeground 之前就置 true，一旦
        // startForeground 失败（Android 14 上 FGS 类型非法、后台启动受限等），
        // 诊断面板会显示 ✅ 绿勾，而服务其实没在跑 —— 假绿比红叉更危险，
        // 会把人引向错误方向排查。真正的置位在 goForeground() 成功分支里。
        goForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 只在「首次进入前台」时调 goForeground()。
        // 早前每次 onStartCommand 都调一次，而 AlarmService.start() 在
        // apply()/scheduleOne()/cancel() 等几乎所有插件方法里都会被调用，
        // 于是每次任务变动都会重复 startForeground（重建通知、唤醒通知服务）
        // —— 这是「待机耗电」的主要来源之一。已在前台就无需重复。
        if (!foregrounded) goForeground();
        // 「知道了」按钮：只撤销通知，不重复投递提醒
        if (intent != null && intent.getIntExtra("ackId", -1) != -1) {
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(intent.getIntExtra("ackId", -1));
            } catch (Exception ignore) {}
            return START_STICKY;
        }
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
            foregrounded = true;
            running = true;
        } catch (Exception e) {
            // 退化：不带类型再试一次
            try {
                startForeground(FG_ID, n);
                foregrounded = true;
                running = true;
            } catch (Exception ignore) {
                // 两次都失败：服务无法成为前台服务。
                // Android 8+ 对 startForegroundService() 启动的服务有 5 秒宽限期，
                // 到期仍未进入前台会抛 RemoteServiceException 直接崩掉进程。
                // 所以这里必须主动 stopSelf() 退出，不能「什么都不做」硬扛。
                // 同时不置 running，让诊断面板如实显示未运行。
                running = false;
                foregrounded = false;
                try { if (nm != null) nm.notify(FG_ID, n); } catch (Exception ignore2) {}
                try { stopSelf(); } catch (Exception ignore3) {}
            }
        }
    }

    /**
     * 构造通知操作按钮，兼容 minSdk=22。
     *
     * Icon.createWithResource() 是 **API 23** 才有的；而本 App minSdk 是 22。
     * 直接调用会在 Android 5.1 上抛 NoSuchMethodError 崩掉整个提醒投递。
     * 这里按版本分支：23+ 用带图标的 Action，22 用不带图标的构造器。
     */
    private Notification.Action action(String title, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_ddlr),
                    title, pi).build();
        }
        // API 22：没有 Icon 类，用旧构造器（无图标）
        return new Notification.Action.Builder(R.drawable.ic_stat_ddlr, title, pi).build();
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
        if (cachedContentPi == null) {
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                cachedContentPi = PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
        }
        return b.setContentTitle("DDL雷达 · 提醒守护中")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_ddlr)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(cachedContentPi)
                .build();
    }

    private void refreshGuard() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n = guardNotification();
            // 文案没变就不重复投递：apply() 每次对账都会调到这里，
            // 频繁 notify 会不断唤醒系统通知服务，是待机耗电的来源之一。
            String text = n.extras.getString(Notification.EXTRA_TEXT);
            if (text != null && text.equals(lastGuardText)) return;
            lastGuardText = text;
            nm.notify(FG_ID, n);
        } catch (Exception e) { /* ignore */ }
    }

    /* ---------------- 闹钟到点：交给系统通知 ---------------- */

    private void fireReminder(Intent intent) {
        int id = intent.getIntExtra("id", -1);
        long at = intent.getLongExtra("at", 0);
        long now = System.currentTimeMillis();

        // 主备双通道去重：备份通道 +2s 到达，窗口收到 8s 足够覆盖
        // （早前 60s 窗口太宽：同一时刻附近的真实连续提醒会被误吞，
        //  而且用户 1 分钟内改了时间再触发也会被当成重复）
        long[] lf = AlarmScheduler.lastFire(this);
        boolean dup = lf[0] == id && now - lf[1] < 8000;
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

    /** 当前正在播放的铃声：持有引用以便 onDestroy 时释放，避免泄漏 */
    private MediaPlayer player;

    private void playChime() {
        try {
            // 音源选择（兼顾「音量键可控」与「静音档不静默」两个诉求）：
            // · 默认用 USAGE_NOTIFICATION —— 走通知/铃声音量流，音量键可直接调，
            //   这是「提示音大小不能用音量键控制」的修复。
            // · 但该流受系统「铃声模式」约束：静音/震动档下会被系统直接吞掉，
            //   于是选了「响铃+震动」却在静音模式下完全无声 —— 比调不动更糟。
            //   检测到静音/震动档时改用 USAGE_ALARM（闹钟流不受铃声模式影响），
            //   保证提醒一定能出声；此时音量由闹钟音量决定。
            int ringer = -1;
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) ringer = am.getRingerMode();
            } catch (Exception ignore) {}
            boolean ringerSilenced = ringer == android.media.AudioManager.RINGER_MODE_SILENT
                    || ringer == android.media.AudioManager.RINGER_MODE_VIBRATE;

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(ringerSilenced ? AudioAttributes.USAGE_ALARM
                                             : AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            releasePlayer(); // 上一条还没放完就先释放，避免叠加播放
            final MediaPlayer p = MediaPlayer.create(this, R.raw.ddlr_chime, attrs, 1);
            if (p == null) return;
            player = p;
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    try { m.release(); } catch (Exception ignore) {}
                    if (player == m) player = null;
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    try { m.release(); } catch (Exception ignore) {}
                    if (player == m) player = null;
                    return true;
                }
            });
            p.start();
        } catch (Exception e) { /* 铃声失败不阻断通知投递 */ }
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { if (p.isPlaying()) p.stop(); } catch (Exception ignore) {}
            try { p.release(); } catch (Exception ignore) {}
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

    /**
     * 确保目标提醒通道存在，且**语义与通道 id 一致**。
     * 只补建缺失的通道，不覆盖已存在的（已存在的设置归用户所有，系统也不允许改写）。
     * 返回实际可用的通道 id（无法识别时回落 ddlr_full）。
     */
    private String ensureReminderChannel(NotificationManager nm, String channelId) {
        String id = channelId;
        if (!"ddlr_vib".equals(id) && !"ddlr_ring".equals(id) && !"ddlr_full".equals(id)) {
            id = "ddlr_full";
        }
        NotificationChannel existing = nm.getNotificationChannel(id);
        if (existing != null) {
            // 已存在但重要性被用户调低（或历史版本建成 LOW）→ 不会弹顶部横幅。
            // 通道重要性 App 改不了，只能引导用户去设置；这里把结果留给诊断面板。
            return id;
        }
        String name;
        boolean sound, vib;
        if ("ddlr_vib".equals(id)) {
            name = "提醒 · 仅震动"; sound = false; vib = true;
        } else if ("ddlr_ring".equals(id)) {
            name = "提醒 · 仅响铃"; sound = true; vib = false;
        } else {
            name = "提醒 · 响铃+震动"; sound = true; vib = true;
        }
        NotificationChannel ch = new NotificationChannel(id, name,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(vib);
        ch.setVibrationPattern(new long[]{0, 200, 120, 200});
        if (sound) {
            // 与 playChime() 保持一致：通知音量流，音量键可调
            ch.setSound(android.net.Uri.parse("android.resource://" + getPackageName() + "/raw/ddlr_chime"),
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
        } else {
            ch.setSound(null, null);
        }
        // HIGH 通道才会弹 heads-up（顶部横幅）；同时显式要求它绕过勿扰的
        // 「仅优先级」限制，否则系统静音模式下横幅仍会被拦。
        try { ch.setBypassDnd(false); } catch (Exception ignore) {}
        nm.createNotificationChannel(ch);
        return id;
    }

    /** 通道重要性是否足以弹顶部横幅（诊断用） */
    static int channelImportance(NotificationManager nm, String id) {
        try {
            NotificationChannel ch = nm.getNotificationChannel(id);
            return ch == null ? -1 : ch.getImportance();
        } catch (Exception e) { return -1; }
    }

    /**
     * 投递提醒通知。
     *
     * 通道语义（与 JS 侧三个通道一一对应，绝不能混用）：
     *   ddlr_full 响铃+震动 / ddlr_vib 仅震动（**必须静音**）/ ddlr_ring 仅响铃
     *
     * 早前这里一旦通道缺失就回落 ddlr_full（带声音），于是「仅震动」模式下
     * 通道若因故缺失，系统会用它补一条带铃声的通道 → 用户反馈的
     * 「选震动那种声音有点大」正是这条回落路径造成的。现在按目标通道
     * 精确重建，绝不跨模式回落。
     */
    private void postBanner(int id, String title, String body, String channelId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (channelId == null || channelId.isEmpty()) channelId = "ddlr_full";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                channelId = ensureReminderChannel(nm, channelId);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, channelId);
            } else {
                // pre-O 无通道概念：按模式设置提示音与震动
                b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);
                if ("ddlr_vib".equals(channelId)) {
                    b.setDefaults(Notification.DEFAULT_VIBRATE);
                } else if ("ddlr_ring".equals(channelId)) {
                    b.setDefaults(Notification.DEFAULT_SOUND);
                } else {
                    b.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
                }
            }
            // 声音/震动已由本服务直执行（playSoundAndVibrate），通道再响一次就是
            // 双响。这里让通知本身不重复发声：通道保持 HIGH 以弹出横幅，
            // 但把该条通知的 sound/vibrate 置空，由直执行唯一负责发声。
            if ("ddlr_vib".equals(channelId)) {
                b.setSound(null);
                b.setVibrate(null);
            } else if ("ddlr_ring".equals(channelId)) {
                b.setVibrate(null);
            }
            // 通知基础属性（在 addAction 之前设置好，最后统一 build）
            String safeTitle = (title == null || title.isEmpty()) ? "DDL雷达" : title;
            String safeBody = body == null ? "" : body;
            b.setContentTitle(safeTitle)
                    .setContentText(safeBody)
                    .setStyle(new Notification.BigTextStyle().bigText(safeBody))
                    .setSmallIcon(R.drawable.ic_stat_ddlr)
                    .setAutoCancel(true)
                    // 用 CATEGORY_REMINDER 而非 CATEGORY_ALARM：
                    // CATEGORY_ALARM 会让部分 ROM（含 ColorOS）走「闹钟特殊通道」，
                    // 表现为只响不弹横幅；REMINDER 是普通提醒语义，配合 HIGH 通道
                    // 才会正常弹 heads-up 顶部横幅。
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setWhen(System.currentTimeMillis());
            PendingIntent pi = null;
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                pi = PendingIntent.getActivity(this, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            b.setContentIntent(pi);
            // 闹钟式全屏提醒：锁屏/灭屏直接亮屏进入 AlarmActivity（系统闹钟同款）
            // 注意：Android 14+ 若用户拒绝了「全屏通知」权限，setFullScreenIntent
            // 会静默失效——通知仍弹但不亮屏。故这里额外在锁屏/灭屏时主动拉起
            // AlarmActivity 兜底（前台服务已持有 START_ACTIVITY 能力）。
            boolean fsiOk = false;
            try {
                Intent alarm = new Intent(this, AlarmActivity.class);
                alarm.putExtra("title", title);
                alarm.putExtra("body", body);
                alarm.putExtra("notifId", notifId(id));
                alarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent fsi = PendingIntent.getActivity(this, 1000000 + id, alarm,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.setFullScreenIntent(fsi, true);
                fsiOk = true;
            } catch (Exception ignore) { /* 全屏 Intent 不可用时退化为普通横幅 */ }
            // ── 操作按钮：解决「震动完不知道该做什么」 ──
            // 只震动/只响铃时用户往往在忙，看到通知却不知道怎么处理。
            // 给两个直接动作：打开 App 处理 / 直接标记已知悉。
            //
            // 必须用 Builder.addAction()，而不是 build() 之后去改 n.actions：
            // Notification.actions 是系统内部维护的字段，build 后再往里 add
            // 不保证被通知服务采纳（部分版本直接忽略）。addAction 才是官方路径。
            try {
                Intent openI = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (openI != null) {
                    openI.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent pOpen = PendingIntent.getActivity(this, 2000000 + id, openI,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    b.addAction(action("去看看", pOpen));
                }
                // 「知道了」：只撤销通知，不再重复响（用户已确认看到）
                Intent ackI = new Intent(this, AlarmService.class);
                ackI.putExtra("ackId", notifId(id));
                PendingIntent pAck = PendingIntent.getService(this, 3000000 + id, ackI,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(action("知道了", pAck));
            } catch (Exception ignore) { /* 按钮加不上不影响通知本体 */ }

            Notification n = b.build();
            nm.notify(notifId(id), n);

            // 锁屏/灭屏兜底亮屏：全屏 Intent 可能因权限被拒而不生效，
            // 这里直接拉起提醒页，保证「锁屏也看得见」。
            // 亮屏时则不拉（避免打断用户正在做的事，横幅已足够）。
            if (fsiOk && isScreenOffOrLocked()) {
                try {
                    Intent alarm = new Intent(this, AlarmActivity.class);
                    alarm.putExtra("title", title);
                    alarm.putExtra("body", body);
                    alarm.putExtra("notifId", notifId(id));
                    alarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    startActivity(alarm);
                } catch (Exception ignore) { /* 后台启动 Activity 被系统限制时忽略 */ }
            }
        } catch (Exception e) { /* 投递失败不影响其他提醒 */ }
    }

    /** 屏幕是否熄灭或处于锁屏状态（决定是否需要主动亮屏拉起提醒页） */
    private boolean isScreenOffOrLocked() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return true;
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return true;
        } catch (Exception ignore) { /* 判断失败时按「不需要」处理，不冒险拉起 */ }
        return false;
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
