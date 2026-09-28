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
     * 注册一条系统闹钟，双机制同发（同一 PendingIntent，接收器 60s 窗口去重）：
     * 主：setAlarmClock（状态栏闹钟图标、免授权、Doze 准时）；
     * 备：setExactAndAllowWhileIdle 在 +3s 再发一次——个别 ROM 对闹钟时钟通道
     * 的派发存在怪异拦截，两条独立通道指向同一接收器，只要一条到达即提醒。
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
        PendingIntent op = operation(ctx, id, at, title, body, channelId);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), op);
        } catch (SecurityException e) {
            // 理论上不可达（setAlarmClock 免授权 + USE_EXACT_ALARM 双保险）；
            // 万一被厂商 ROM 拦截则退化为普通闹钟，提醒仍可达只是 Doze 下可能略偏
            am.set(AlarmManager.RTC_WAKEUP, at, op);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 3000, op);
                }
            } catch (SecurityException e) { /* 主通道在即可 */ }
        }
    }

    /** 取消一条闹钟（只注销闹钟，不改持久化列表——由调用方整表协调；filterEquals 不比对 extras，
     *  主备两条闹钟共用同一 PendingIntent 身份，一次 cancel 同时注销） */
    public static void cancel(Context ctx, long id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(operation(ctx, id, 0, null, null, null));
    }

    /** 开机/改时间/替换安装后恢复所有未来闹钟，返回最近的触发时刻（无则 0） */
    public static long reapplyAll(Context ctx) {
        JSONArray arr = load(ctx);
        long now = System.currentTimeMillis();
        JSONArray keep = new JSONArray();
        long nextAt = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("at", 0);
            if (at <= now) continue; // 已过期：丢弃（App 内引擎会自行补弹错过提示）
            keep.put(o);
            schedule(ctx, o.optLong("id"), at,
                    o.optString("title", null), o.optString("body", null),
                    o.optString("channel", null));
            if (nextAt == 0 || at < nextAt) nextAt = at;
        }
        if (keep.length() != arr.length()) save(ctx, keep);
        return nextAt;
    }

    private static PendingIntent operation(Context ctx, long id, long at,
                                           String title, String body, String channelId) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.putExtra("id", (int) id);
        i.putExtra("at", at);
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        if (channelId != null) i.putExtra("channelId", channelId);
        return PendingIntent.getBroadcast(ctx, (int) id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
