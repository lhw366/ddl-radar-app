package com.ddlradar.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

/**
 * 系统闹钟调度核心：AlarmManager.setAlarmClock()（系统时钟同款 API）。
 * - 精确闹钟权限可用时使用 setAlarmClock；否则降级为允许休眠期间执行的闹钟
 * - 状态栏显示闹钟图标（点按直达 App），系统视为用户真正的闹钟
 * - 闹钟列表持久化在 SharedPreferences，开机/改时间/替换安装后由
 *   BootReceiver 调 reapplyAll() 自动恢复（AlarmManager 闹钟不跨重启）
 */
public final class AlarmScheduler {

    /**
     * 闹钟注册规格版本：变更后首次 apply 会全量重排。
     * v3 = 主备通道改用独立 requestCode（修「备份通道顶掉 setAlarmClock 导致延迟」）
     * v4 = apply() 不再撤销临期闹钟（修「到点前被撤、60s 后才补弹」）
     * v5 = 系统只调度最近 120 条，其余保存在原生队列中逐条递补
     * v7 = 响铃/震动/横幅独立设置，旧版闹钟按新通道与震动标记重排
     * 放在这里（而不是 DdlAlarmPlugin）是为了让 reapplyAll 也能写入最新版本号：
     * 早前 reapplyAll 把条目原样写回、不更新 v，于是旧版存下的条目 v 永远是旧值，
     * sameSpec 比对永远失败 → 每次开机后第一次 apply() 都把全部闹钟 cancel+重排。
     */
    public static final int SCHED_VER = 7;

    /** Keep the number of active system alarms bounded while retaining overflow locally. */
    public static final int MAX_ACTIVE_ALARMS = 120;

    private static final String PREFS = "ddlr_alarms";
    private static final String KEY = "alarms";
    private static final Comparator<JSONObject> BY_TRIGGER_TIME = new Comparator<JSONObject>() {
        @Override
        public int compare(JSONObject left, JSONObject right) {
            long a = left.optLong("at", 0);
            long b = right.optLong("at", 0);
            return a < b ? -1 : (a == b ? 0 : 1);
        }
    };

    private AlarmScheduler() {}

    static void sortByTriggerTime(List<JSONObject> items) {
        Collections.sort(items, BY_TRIGGER_TIME);
    }

    /** 读已排定的闹钟列表：[{id, at, title, body, channel}] */
    public static synchronized JSONArray load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(sp.getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 整表覆写持久化列表 */
    public static synchronized void save(Context ctx, JSONArray arr) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.edit().putString(KEY, arr.toString()).commit()) {
            throw new IllegalStateException("Unable to persist reminder schedule");
        }
    }

    /** A consumed occurrence must not be re-added by an in-flight JS reconciliation. */
    static boolean wasDelivered(Context ctx, long id, long at) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("delivered_" + id, -1) == at;
    }

    static void recordDelivered(Context ctx, long id, long at) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = sp.edit().putLong("delivered_" + id, at);
        long oldest = System.currentTimeMillis() - 24 * 60 * 60000L;
        for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            if (entry.getKey().startsWith("delivered_") && entry.getValue() instanceof Long
                    && ((Long) entry.getValue()) < oldest) edit.remove(entry.getKey());
        }
        if (!edit.commit()) throw new IllegalStateException("Unable to record reminder delivery");
    }

    static JSONObject findOccurrence(Context ctx, long id, long at) {
        JSONArray items = load(ctx);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optLong("id", -1) == id && item.optLong("at", -1) == at) {
                return item;
            }
        }
        return null;
    }

    /** Keep local wall-clock time across daylight-saving changes. */
    static JSONObject nextOccurrence(JSONObject item, long after) {
        int days = item.optInt("repeatDays", 0);
        if (days != 1 && days != 7 && days != 14) return null;
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(item.optLong("at"));
            do { calendar.add(Calendar.DAY_OF_YEAR, days); }
            while (calendar.getTimeInMillis() <= after);
            long at = calendar.getTimeInMillis();
            long until = item.optLong("repeatUntil", 0);
            if (until > 0 && at > until) return null;
            JSONObject next = new JSONObject(item.toString());
            next.put("at", at);
            next.put("scheduled", false);
            next.put("v", SCHED_VER);
            return next;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot advance recurring reminder", error);
        }
    }

    static JSONObject currentOccurrence(Context ctx, JSONObject item, long now) {
        long at = item.optLong("at");
        if (wasDelivered(ctx, item.optLong("id"), at)) {
            return nextOccurrence(item, Math.max(at, now - 15 * 60000L));
        }
        if (at < now - 15 * 60000L) {
            return nextOccurrence(item, now - 15 * 60000L);
        }
        return item;
    }

    static void completeOccurrence(Context ctx, JSONObject item) {
        long id = item.optLong("id");
        JSONArray items = load(ctx);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject stored = items.optJSONObject(i);
            if (stored != null && stored.optLong("id") != id) keep.put(stored);
        }
        JSONObject next = nextOccurrence(item, System.currentTimeMillis());
        if (next != null) keep.put(next);
        // Persist the next occurrence before cancelling/replacing the old alarms.
        save(ctx, keep);
        cancel(ctx, id);
        promoteQueued(ctx);
    }

    /** 从持久化列表里删除一条（闹钟触发后调用） */
    public static synchronized void remove(Context ctx, long id) {
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

    /** Fill a freed system-alarm slot from the earliest queued reminder. */
    public static synchronized void promoteQueued(Context ctx) {
        JSONArray arr = load(ctx);
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null) ordered.add(item);
        }
        Collections.sort(ordered, BY_TRIGGER_TIME);

        long now = System.currentTimeMillis();
        int active = 0;
        for (JSONObject item : ordered) {
            if (item.optBoolean("scheduled", true) && item.optLong("at", 0) > now - 15 * 60000L) {
                active++;
            }
        }

        JSONArray keep = new JSONArray();
        boolean changed = false;
        for (JSONObject original : ordered) {
            JSONObject item = currentOccurrence(ctx, original, now);
            if (item == null) {
                cancel(ctx, original.optLong("id"));
                changed = true;
                continue;
            }
            if (item != original) changed = true;
            long at = item.optLong("at", 0);
            boolean scheduled = item.optBoolean("scheduled", true);
            if (at < now - 15 * 60000L) {
                if (scheduled) cancel(ctx, item.optLong("id"));
                changed = true;
                continue;
            }
            if (!scheduled && active < MAX_ACTIVE_ALARMS) {
                schedule(ctx, item.optLong("id"), at,
                        item.optString("title", null), item.optString("body", null),
                        item.optString("channel", null), item.optBoolean("vibrate", true));
                try { item.put("scheduled", true); } catch (Exception ignore) {}
                active++;
                changed = true;
            }
            keep.put(item);
        }
        if (changed) save(ctx, keep);
    }

    /** 闹钟实际触发留痕（接收器去重后调用，后续任何失败都不影响此记录） */
    public static void markReceiverHit(Context ctx, long id, String via) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putLong("lastReceiverId", id)
                    .putLong("lastReceiverAt", System.currentTimeMillis())
                    .putString("lastReceiverVia", via == null ? "" : via)
                    .apply();
        } catch (Exception e) { /* 诊断数据，失败忽略 */ }
    }

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

    public static long[] lastReceiver(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new long[]{sp.getLong("lastReceiverId", 0), sp.getLong("lastReceiverAt", 0)};
        } catch (Exception e) {
            return new long[]{0, 0};
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
    public static synchronized void schedule(Context ctx, long id, long at,
                                String title, String body, String channelId, boolean vibrate) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) throw new IllegalStateException("Alarm manager unavailable");
        cancelLegacy(ctx, id);

        // showIntent：状态栏闹钟图标点按后打开 App
        PendingIntent show = null;
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            show = PendingIntent.getActivity(ctx, (int) id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        PendingIntent op = operation(ctx, id, at, title, body, channelId, vibrate, 0);

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
                    am.setExact(AlarmManager.RTC_WAKEUP, at, op);
                }
            } catch (SecurityException e2) {
                // 精确闹钟同样被拒：最后退回 inexact。这一层必须自己兜住，
                // 否则异常会冒出 schedule() 打断后续备份通道注册，并被
                // DdlAlarmPlugin.apply() 的 try 捕获后 reject 整次调用——
                // 那会导致整表闹钟一条都排不上，远比「不准时」严重。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, at, op);
                }
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
                        operation(ctx, id, at, title, body, channelId, vibrate, 1));
            } catch (Exception ignore) { /* 主通道在即可 */ }
        }
    }

    /** 取消一条闹钟：主备两条都要注销（独立 requestCode 后不再共享身份） */
    public static synchronized void cancel(Context ctx, long id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(operation(ctx, id, 0, null, null, null, false, 0));
        am.cancel(operation(ctx, id, 0, null, null, null, false, 1));
        cancelLegacy(ctx, id);
    }

    /**
     * 开机/改时间/替换安装后恢复所有未来闹钟，返回最近的触发时刻（无则 0）。
     *
     * 注意「已过期」的条目：早前直接丢弃，但用户如果因为关机/重启错过了提醒，
     * 那条记录就彻底消失、也没有任何提示。现在把它们记进 missedAt，
     * 由 App 打开时读取并补弹一条「你错过了 N 条提醒」，避免静默丢失。
     */
    public static synchronized long reapplyAll(Context ctx) {
        JSONArray arr = load(ctx);
        long now = System.currentTimeMillis();
        JSONArray keep = new JSONArray();
        long nextAt = 0;
        int missed = 0;
        long missedLatest = 0;
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) ordered.add(o);
        }
        Collections.sort(ordered, BY_TRIGGER_TIME);
        int active = 0;
        for (JSONObject original : ordered) {
            long at = original.optLong("at", 0);
            JSONObject o = currentOccurrence(ctx, original, now);
            if (at < now - 15 * 60000L && !wasDelivered(ctx, original.optLong("id"), at)
                    && now - at < 24 * 3600 * 1000L) missed++;
            if (o == null) {
                // 过期：计入错过统计。
                // 只统计「早于当前时间 1 分钟以上」的：刚过点几秒的条目可能
                // 正在投递中（DdlAlarmPlugin 刻意保留 2 分钟窗口），
                // 计成 missed 会造成「明明响了却说错过」的误报。
                // 同时只统计 24 小时内的，更早的没意义。
                long overdue = now - at;
                cancel(ctx, original.optLong("id"));
                continue;
            }
            at = o.optLong("at", 0);
            boolean scheduled = active < MAX_ACTIVE_ALARMS;
            if (scheduled) {
                schedule(ctx, o.optLong("id"), at,
                        o.optString("title", null), o.optString("body", null),
                        o.optString("channel", null), o.optBoolean("vibrate", true));
                active++;
                if (nextAt == 0) nextAt = at;
            } else if (o.optBoolean("scheduled", true)) {
                cancel(ctx, o.optLong("id"));
            }
            try {
                o.put("v", SCHED_VER);
                o.put("scheduled", scheduled);
            } catch (Exception ignore) {}
            keep.put(o);
        }
        save(ctx, keep);
        // 累加而不是覆盖：改时区可能连续收到两条广播，覆盖会丢掉前一次的计数。
        if (missed > 0) addMissed(ctx, missed);
        return nextAt;
    }

    /**
     * 读取「错过的提醒」计数。
     *
     * @param consume true=读取后清零（App 启动时补弹一次用），
     *                false=只读（诊断面板显示用，不清零）。
     * 早前只有一个「读即清零」的版本，且唯一调用方是诊断面板的 pending()，
     * 结果用户只是打开诊断看一眼，错过计数就被清掉了，
     * 而承诺的「启动时补弹提示」根本没实现 —— 语义被消费方吃掉了。
     */
    public static int getMissed(Context ctx, boolean consume) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int n = sp.getInt("missedCount", 0);
            if (n > 0 && consume) sp.edit().putInt("missedCount", 0).apply();
            return n;
        } catch (Exception e) { return 0; }
    }

    /** 累加错过计数（多次事件不互相覆盖） */
    public static void addMissed(Context ctx, int delta) {
        if (delta <= 0) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int cur = sp.getInt("missedCount", 0);
            sp.edit().putInt("missedCount", cur + delta).apply();
        } catch (Exception ignore) {}
    }

    /**
     * 构造投递用 PendingIntent。
     *
     * @param slot 0=主通道（setAlarmClock），1=备份通道（setExactAndAllowWhileIdle）。
     *             两者必须用不同 requestCode，否则系统按「同一 PendingIntent」
     *             处理，后注册的会覆盖前一个闹钟（详见 schedule 注释）。
     */
    static PendingIntent operation(Context ctx, long id, long at,
                                           String title, String body, String channelId,
                                           boolean vibrate, int slot) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.ddlradar.app.DELIVER_REMINDER");
        // Extras do not participate in PendingIntent identity. Preserve the full id,
        // with a separate URI for each route, instead of truncating it to 30 bits.
        i.setData(Uri.parse("ddlr://alarm/" + id + "/" + slot));
        i.putExtra("id", (int) id);
        i.putExtra("at", at);
        i.putExtra("slot", slot);
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        if (channelId != null) i.putExtra("channelId", channelId);
        i.putExtra("vibrate", vibrate);
        return PendingIntent.getBroadcast(ctx, slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Retire v5 and older service PendingIntents when migrating or cancelling. */
    private static void cancelLegacy(Context ctx, long id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int slot = 0; slot < 2; slot++) {
            int request = (((int) id & 0x3FFFFFFF) << 1) | slot;
            Intent intent = new Intent(ctx, AlarmService.class);
            int flags = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent legacy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? PendingIntent.getForegroundService(ctx, request, intent, flags)
                    : PendingIntent.getBroadcast(ctx, request, intent, flags);
            if (legacy != null) {
                am.cancel(legacy);
                legacy.cancel();
            }
        }
    }
}
