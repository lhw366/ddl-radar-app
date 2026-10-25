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
 * User-visible reminders use one AlarmClock per occurrence; no duplicate backup
 * or polling alarm. Android may exit Doze shortly before an AlarmClock fires.
 * Exact-idle fallback can also run during Doze but has frequency restrictions.
 * Persisted schedules are restored after reboot, time changes and app updates.
 * OEM behavior and battery impact require device measurements, not API guesses.
 */
public final class AlarmScheduler {

    /**
     * 闹钟注册规格版本：变更后首次 apply 会全量重排。
     * v3 = 主备通道改用独立 requestCode（修「备份通道顶掉 setAlarmClock 导致延迟」）
     * v4 = apply() 不再撤销临期闹钟（修「到点前被撤、60s 后才补弹」）
     * v5 = 系统只调度最近 120 条，其余保存在原生队列中逐条递补
     * v7 = 响铃/震动/横幅独立设置，旧版闹钟按新通道与震动标记重排
     * v8 = 同时刻备份、临期短唤醒、新版横幅通道
     * v10 = 单路 exact-idle 提醒，退役闹钟时钟与预唤醒，保护系统休眠
     * v11 = 回到单路 setAlarmClock（不再有 exact 备份通道）：v10 的 exact-idle
     *       通道可能受系统频率/ROM 限制，实际问题应依据设备日志判断。
     *       单通道不变；AlarmClock 可能提前退出 Doze，不能假定功耗与 v10 一样。
     * 放在这里（而不是 DdlAlarmPlugin）是为了让 reapplyAll 也能写入最新版本号：
     * 早前 reapplyAll 把条目原样写回、不更新 v，于是旧版存下的条目 v 永远是旧值，
     * sameSpec 比对永远失败 → 每次开机后第一次 apply() 都把全部闹钟 cancel+重排。
     */
    // v16: the due alarm starts the finite foreground service directly.
    public static final int SCHED_VER = 16;

    /** 最近一次注册实际使用的通道（诊断用）：clock / exact_idle / inexact_idle */
    private static final String KEY_SCHED_MODE = "schedMode";

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
        appendDeliveredLog(ctx, id, at);
    }

    /** 已投递事件的滚动日志 [[id, at], ...]，供 JS 在前台补弹前做去重。 */
    private static final String KEY_DELIVERED_LOG = "deliveredLog";
    private static final int DELIVERED_LOG_MAX = 60;

    /**
     * 最近被原生投递过的事件（按时间升序）。
     *
     * 为什么需要：JS 侧 tickReminders 的补弹必须能判断「这条到底是不是原生已经响过」。
     * 早前只能靠「5 秒宽限 + 后台定时器被暂停」间接猜，结果要么重复弹（用户抱怨
     * 重复震动），要么干脆整体禁用返回（用户抱怨切后台彻底不响）。
     * 有了这份日志，JS 可以精确地：原生已送达 → 只标记不重弹；原生没送达 → 补弹。
     */
    public static JSONArray deliveredLog(Context ctx) {
        try {
            return new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DELIVERED_LOG, "[]"));
        } catch (Exception error) { return new JSONArray(); }
    }

    private static void appendDeliveredLog(Context ctx, long id, long at) {
        try {
            JSONArray old = deliveredLog(ctx);
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, old.length() - (DELIVERED_LOG_MAX - 1)); i < old.length(); i++) {
                out.put(old.get(i));
            }
            out.put(new JSONArray().put(id).put(at));
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DELIVERED_LOG, out.toString()).apply();
        } catch (Exception ignore) { }
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
                        item.optString("channel", null), item.optBoolean("vibrate", true),
                        item.optBoolean("vibrateInSilent", true));
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

    /** 最近一次触发走的派发通道："clock"（系统闹钟）/ "exact"（降级精确闹钟）/ "service" */
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
     * 注册一条提醒：优先系统闹钟时钟，权限不足时逐级降级。OEM 投递仍须实测。
     * 无论走到哪一级，每个事件仅注册一条系统闹钟。
     */
    public static synchronized void schedule(Context ctx, long id, long at,
                                String title, String body, String channelId, boolean vibrate) {
        schedule(ctx, id, at, title, body, channelId, vibrate, true);
    }

    public static synchronized void schedule(Context ctx, long id, long at,
                                String title, String body, String channelId, boolean vibrate, boolean vibrateInSilent) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) throw new IllegalStateException("Alarm manager unavailable");
        cancelLegacy(ctx, id);
        // 退役通道：v8 的同时刻备份（slot 1）与预唤醒（slot 2）。
        // 只需注销身份，不必再注册——它们的存在本身就是耗电来源。
        am.cancel(operation(ctx, id, 0, null, null, null, false, 1));
        am.cancel(operation(ctx, id, 0, null, null, null, false, 2));
        PendingIntent op = operation(ctx, id, at, title, body, channelId, vibrate, vibrateInSilent, 0);
        String mode;
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(ctx)), op);
            mode = "clock";
        } catch (SecurityException clockDenied) {
            // Android 12+ 用户撤销了「闹钟和提醒」，或个别 ROM 自定义拦截。
            // 精确空闲闹钟也可在 Doze 派发，但受频率等系统限制。
            try {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
                else am.setExact(AlarmManager.RTC_WAKEUP, at, op);
                mode = "exact_idle";
            } catch (SecurityException exactDenied) {
                // 精确闹钟也被拒：最后一层必须自己兜住，否则异常会冒出 schedule()
                // 打断 apply() 的整表对账，结果是一条闹钟都排不上。
                // Inexact alarms do NOT receive the exact-alarm FGS exemption.
                // Keep notification-only legacy delivery if exact access was denied.
                PendingIntent fallback = operation(ctx, id, at, title, body, channelId, vibrate, vibrateInSilent, 3);
                if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fallback);
                else am.set(AlarmManager.RTC_WAKEUP, at, fallback);
                am.cancel(op);
                mode = "inexact_idle";
            }
        }
        // Register the service operation BEFORE retiring both broadcast identities.
        cancelBackgroundBroadcast(ctx, id);
        if (!"inexact_idle".equals(mode)) am.cancel(operation(ctx, id, 0, null, null, null, false, 3));
        recordSchedMode(ctx, mode);
        ReminderTrace.mark(ctx, id, "scheduled", "at=" + at + ";mode=" + mode + ";route="
                + ("inexact_idle".equals(mode) ? "inexact_broadcast" : "foreground_service"));
    }

    /** 状态栏闹钟图标的点按目标（所有闹钟共用一个，避免逐条 PendingIntent 抖动）。 */
    private static PendingIntent showIntent(Context ctx) {
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open == null) return null;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 7000, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void recordSchedMode(Context ctx, String mode) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SCHED_MODE, mode == null ? "" : mode).apply();
        } catch (Exception ignore) { }
    }

    /** 最近一次注册实际生效的通道：clock / exact_idle / inexact_idle（空=未知） */
    public static String lastSchedMode(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SCHED_MODE, "");
        } catch (Exception error) { return ""; }
    }

    /** Device-wide next alarm, possibly owned by another app; not proof of ours. */
    public static long nextClockAt(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return 0;
            AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
            return info == null ? 0 : info.getTriggerTime();
        } catch (Exception error) { return 0; }
    }

    /** 取消一条闹钟：主备两条都要注销（独立 requestCode 后不再共享身份） */
    public static synchronized void cancel(Context ctx, long id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(operation(ctx, id, 0, null, null, null, false, 0));
        am.cancel(operation(ctx, id, 0, null, null, null, false, 1));
        am.cancel(operation(ctx, id, 0, null, null, null, false, 2));
        am.cancel(operation(ctx, id, 0, null, null, null, false, 3));
        cancelBackgroundBroadcast(ctx, id);
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
                        o.optString("channel", null), o.optBoolean("vibrate", true),
                        o.optBoolean("vibrateInSilent", true));
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
     * @param slot 0=当前唯一在用的投递通道；1=已退役的同时刻备份；2=已退役的预唤醒。
     *             历史上有过多通道设计，两者必须用不同 requestCode，否则系统按
     *             「同一 PendingIntent」处理，后注册的会覆盖前一个闹钟。
     *             slot 3 only replaces slot 0 when exact alarm access is denied.
     *             slot 1/2 are only used to cancel retired identities.
     */
    static PendingIntent operation(Context ctx, long id, long at,
                                           String title, String body, String channelId,
                                           boolean vibrate, int slot) {
        return operation(ctx, id, at, title, body, channelId, vibrate, true, slot);
    }

    static PendingIntent operation(Context ctx, long id, long at,
                                           String title, String body, String channelId,
                                           boolean vibrate, boolean vibrateInSilent, int slot) {
        Intent i = new Intent(ctx, slot == 0 ? AlarmService.class : AlarmReceiver.class);
        i.setAction(slot == 0 ? AlarmService.ACTION_DUE : "com.ddlradar.app.DELIVER_REMINDER");
        // Extras do not participate in PendingIntent identity. Preserve the full id,
        // with a separate URI for each route, instead of truncating it to 30 bits.
        i.setData(Uri.parse("ddlr://alarm/" + id + "/" + slot + (slot == 0 ? "/service" : "")));
        i.putExtra("id", (int) id);
        i.putExtra("at", at);
        i.putExtra("slot", slot);
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        if (channelId != null) i.putExtra("channelId", channelId);
        i.putExtra("vibrate", vibrate);
        i.putExtra("vibrateInSilent", vibrateInSilent);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (slot == 0) return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(ctx, slot, i, flags)
                : PendingIntent.getService(ctx, slot, i, flags);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        return PendingIntent.getBroadcast(ctx, slot, i, flags);
    }

    /** Retire v15 urgent and v14 plain broadcasts without creating either. */
    private static void cancelBackgroundBroadcast(Context ctx, long id) {
        for (String suffix : new String[]{"", "/urgent"}) {
            Intent old = new Intent(ctx, AlarmReceiver.class)
                    .setAction("com.ddlradar.app.DELIVER_REMINDER")
                    .setData(Uri.parse("ddlr://alarm/" + id + "/0" + suffix));
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 0, old,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pending != null) {
                AlarmManager manager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (manager != null) manager.cancel(pending);
                pending.cancel();
            }
        }
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
