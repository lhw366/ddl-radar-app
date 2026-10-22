package com.ddlradar.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 系统闹钟调度核心：AlarmManager.setAlarmClock()（系统时钟同款 API）。
 * - 免精确闹钟授权（配合 USE_EXACT_ALARM 双保险），Doze 深度休眠保证准时
 * - 状态栏显示闹钟图标（点按直达 App），系统视为用户真正的闹钟
 * - 闹钟列表持久化在 SharedPreferences，开机/改时间/替换安装后由
 *   BootReceiver 调 reapplyAll() 自动恢复（AlarmManager 闹钟不跨重启）
 */
public final class AlarmScheduler {

    private static final String PREFS = "ddlr_alarms";
    private static final String KEY = "alarms";

    private AlarmScheduler() {}

    /** 读已排定的闹钟列表：[{id, at, title, body, channel}] */
    public static JSONArray load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(sp.getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 整表覆写持久化列表 */
    public static void save(Context ctx, JSONArray arr) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) { /* 存储失败不影响已注册的闹钟 */ }
    }

    /** 从持久化列表里删除一条（闹钟触发后调用） */
    public static void remove(Context ctx, long id) {
        JSONArray arr = load(ctx);
        JSONArray keep = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) { changed = true; continue; }
            if (o.optLong("id", -1) == id) { changed = true; continue; }
            keep.put(o);
        }
        if (changed) save(ctx, keep);
    }

    /** 闹钟实际触发留痕（接收器去重后调用，后续任何失败都不影响此记录） */
    public static void markFired(Context ctx, long id, String via) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putLong("lastFireId", id)
                    .putLong("lastFireAt", System.currentTimeMillis())
                    .putString("lastFireVia", via == null ? "" : via)
                    .apply();
        } catch (Exception e) { /* 诊断数据，失败忽略 */ }
    }

    /** 最近一次闹钟触发记录：[id, at]；从未触发返回 [0, 0] */
    public static long[] lastFire(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new long[]{sp.getLong("lastFireId", 0), sp.getLong("lastFireAt", 0)};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    /** 最近一次触发走的派发通道："clock"（系统闹钟）/ "exact"（精确闹钟备份） */
    public static String lastFireVia(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString("lastFireVia", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 注册一条系统闹钟，双机制同发：
     * 主：setAlarmClock（状态栏闹钟图标、免授权、Doze 准时）；
     * 备：setExactAndAllowWhileIdle 在 +2s 再发一次——个别 ROM 对闹钟时钟通道
     * 的派发存在怪异拦截，两条独立通道指向同一接收器，只要一条到达即提醒。
     *
     * 注意备份通道必须用**独立 requestCode** 的 PendingIntent：
     * 早前主备共用同一 PendingIntent，系统视为同一闹钟的重复设置，
     * 后注册的 setExactAndAllowWhileIdle 会把 setAlarmClock 顶掉，
     * 导致「状态栏闹钟图标消失 + 退化为精确闹钟（Doze 下有分钟级延迟）」，
     * 这正是用户反馈「延迟有点高」的根因。
     */
    public static void schedule(Context ctx, long id, long at,
                                String title, String body, String channelId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // showIntent：状态栏闹钟图标点按后打开 App
        PendingIntent show = null;
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            show = PendingIntent.getActivity(ctx, (int) id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        PendingIntent op = operation(ctx, id, at, title, body, channelId, 0);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), op);
        } catch (SecurityException e) {
            // 降级路径：setAlarmClock 被拒（Android 12/12L 上用户撤销「闹钟和提醒」
            // 权限，或个别 ROM 自定义拦截）。此时改用精确闹钟而非 set()：
            // set() 是 inexact 闹钟，Doze 下会被推迟到下一个维护窗口（小时级），
            // 而 setExactAndAllowWhileIdle 每个 Doze 窗口仍能保证触发一次。
            // 此处 op 是「尚未注册成功」的身份，不存在与其他闹钟互相覆盖的问题。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, at, op);
                }
            } catch (SecurityException e2) {
                // 精确闹钟同样被拒：最后退回 inexact。这一层必须自己兜住，
                // 否则异常会冒出 schedule() 打断后续备份通道注册，并被
                // DdlAlarmPlugin.apply() 的 try 捕获后 reject 整次调用——
                // 那会导致整表闹钟一条都排不上，远比「不准时」严重。
                try { am.set(AlarmManager.RTC_WAKEUP, at, op); } catch (Exception ignore) {}
            }
        }
        // 备份通道：+2s 兜底。用独立 requestCode，绝不与主闹钟共用 PendingIntent
        // （共用会让后注册的精确闹钟顶掉 setAlarmClock，反而失去 Doze 准时性）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // 不用 canScheduleExactAlarms() 预判：该 API 仅 Android 12+ 存在，
                // 且在「主通道刚被拒」的场景下必然返回 false，会导致最需要兜底时
                // 反而跳过兜底。改为直接尝试、由异常决定是否可用。
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 2000,
                        operation(ctx, id, at, title, body, channelId, 1));
            } catch (Exception ignore) { /* 主通道在即可 */ }
        }
    }

    /** 取消一条闹钟：主备两条都要注销（独立 requestCode 后不再共享身份） */
    public static void cancel(Context ctx, long id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(operation(ctx, id, 0, null, null, null, 0));
        am.cancel(operation(ctx, id, 0, null, null, null, 1));
    }

    /**
     * 开机/改时间/替换安装后恢复所有未来闹钟，返回最近的触发时刻（无则 0）。
     *
     * 注意「已过期」的条目：早前直接丢弃，但用户如果因为关机/重启错过了提醒，
     * 那条记录就彻底消失、也没有任何提示。现在把它们记进 missedAt，
     * 由 App 打开时读取并补弹一条「你错过了 N 条提醒」，避免静默丢失。
     */
    public static long reapplyAll(Context ctx) {
        JSONArray arr = load(ctx);
        long now = System.currentTimeMillis();
        JSONArray keep = new JSONArray();
        long nextAt = 0;
        int missed = 0;
        long missedLatest = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("at", 0);
            if (at <= now) {
                // 过期：计入错过统计（只统计 24 小时内的，更早的没意义）
                if (now - at < 24 * 3600 * 1000L) {
                    missed++;
                    if (at > missedLatest) missedLatest = at;
                }
                continue;
            }
            keep.put(o);
            schedule(ctx, o.optLong("id"), at,
                    o.optString("title", null), o.optString("body", null),
                    o.optString("channel", null));
            if (nextAt == 0 || at < nextAt) nextAt = at;
        }
        if (keep.length() != arr.length()) save(ctx, keep);
        if (missed > 0) {
            try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("missedCount", missed)
                        .putLong("missedAt", missedLatest)
                        .apply();
            } catch (Exception ignore) {}
        }
        return nextAt;
    }

    /** 读取并清空「错过的提醒」计数（App 打开时调用，用于补弹提示） */
    public static int takeMissed(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int n = sp.getInt("missedCount", 0);
            if (n > 0) sp.edit().putInt("missedCount", 0).apply();
            return n;
        } catch (Exception e) { return 0; }
    }

    /**
     * 构造投递用 PendingIntent。
     *
     * @param slot 0=主通道（setAlarmClock），1=备份通道（setExactAndAllowWhileIdle）。
     *             两者必须用不同 requestCode，否则系统按「同一 PendingIntent」
     *             处理，后注册的会覆盖前一个闹钟（详见 schedule 注释）。
     */
    private static PendingIntent operation(Context ctx, long id, long at,
                                           String title, String body, String channelId, int slot) {
        Intent i = new Intent(ctx, AlarmService.class);
        i.putExtra("id", (int) id);
        i.putExtra("at", at);
        i.putExtra("slot", slot);
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        if (channelId != null) i.putExtra("channelId", channelId);
        // requestCode 只需「同一 id 下主备不同」，且要避开溢出：
        // id 来自 JS 的 hashId()（上限 2^31-1），直接 *2 会溢出成负数，
        // 虽然负值合法但与别的 id 撞码概率上升。这里用低 30 位左移 + slot，
        // 保证不同 id 的码段不重叠（id 的低 30 位最多 2^30 个，乘以 2 落在 int 内）。
        int reqCode = (((int) id & 0x3FFFFFFF) << 1) | (slot & 1);
        // O+ 直接以「前台服务」身份拉起：ColorOS 冻结后台进程时会扣广播，
        // 但闹钟触发的 FGS 拉起必须放行（真闹钟 App 的标准做法）；pre-O 走广播委托
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(ctx, reqCode, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getBroadcast(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
