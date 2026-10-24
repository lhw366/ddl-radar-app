package com.ddlradar.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * DDL雷达 原生系统闹钟桥（替代 Capacitor LocalNotifications 的调度职责）。
 * 调度统一走 AlarmManager.setExactAndAllowWhileIdle()（见 AlarmScheduler）。
 *
 * JS 侧每次任务/设置变化后调用 apply() 整表协调：
 * 以 JS 算出的期望列表为准，已存在且内容未变的跳过，多退少补，
 * 避免「删光重排」造成的状态栏闹钟时间闪烁与无谓系统调用。
 */
@CapacitorPlugin(name = "DdlAlarm")
public class DdlAlarmPlugin extends Plugin {

    /**
     * 闹钟注册规格版本：变更后 first apply 全量重排。
     * v3 = 主备通道改用独立 requestCode（修「备份通道顶掉 setAlarmClock 导致延迟」）
     * v4 = apply() 不再撤销临期闹钟（修「到点前被撤、60s 后才补弹」）
     * v5 = 最近 120 条进入系统闹钟，超出部分排队递补
     * 常量定义在 AlarmScheduler 里共用：reapplyAll 也要写同一个版本号，
     * 两处各写一份会漂移（早前就是因此导致开机后必然全量重排）。
     */
    private static final int SCHED_VER = AlarmScheduler.SCHED_VER;

    @PluginMethod
    public void apply(PluginCall call) {
        try {
            Context ctx = getContext();
            JSArray in = call.getArray("alarms");
            // 防御：alarms 键缺失（而不是「传了空数组」）说明调用方出了 bug，
            // 此时若照常按空列表对账，会把用户所有已排定闹钟全部 cancel。
            // 明确区分「没传」与「传了空数组」——后者是合法语义（用户删光了任务）。
            if (in == null) {
                call.reject("alarms is required (use [] to clear)");
                return;
            }
            synchronized (AlarmScheduler.class) {
            JSONArray wanted = new JSONArray();
            HashMap<Long, JSONObject> wantedMap = new HashMap<>();
            long nextAt = 0;

            for (int i = 0; i < in.length(); i++) {
                JSONObject w = normalize(in.optJSONObject(i));
                if (w == null) continue;
                w = AlarmScheduler.currentOccurrence(ctx, w, System.currentTimeMillis());
                if (w == null) continue;
                long id = w.optLong("id");
                if (wantedMap.containsKey(id)) continue; // id 去重
                wantedMap.put(id, w);
                wanted.put(w);
            }

            List<JSONObject> ordered = new ArrayList<>();
            for (int i = 0; i < wanted.length(); i++) {
                JSONObject item = wanted.optJSONObject(i);
                if (item != null) ordered.add(item);
            }
            AlarmScheduler.sortByTriggerTime(ordered);
            wanted = new JSONArray();
            wantedMap.clear();
            for (JSONObject item : ordered) {
                wanted.put(item);
                wantedMap.put(item.optLong("id"), item);
                long at = item.optLong("at");
                if (nextAt == 0 || at < nextAt) nextAt = at;
            }

            JSONArray stored;
            HashMap<Long, JSONObject> storedMap = new HashMap<>();

            int cancelled = 0, added = 0, queued = 0;
            long nowMs = System.currentTimeMillis();
            // ── 顺序很重要：先「注册新的/需要改的」，再「取消多余的」 ──
            // 早前是「先全量 cancel，再 schedule」，两者之间存在时间窗口：
            // 若某条提醒恰好在 cancel 之后、schedule 之前的这几毫秒到点，
            // AlarmManager 已经把它摘掉 → 这次提醒永久丢失。
            // 表现为「有时准、有时不准」，且完全取决于恰不恰好卡在这个窗口。
            // 现在的顺序保证任何时刻该响的闹钟都至少有一条存活。
            synchronized (AlarmScheduler.class) {
                stored = AlarmScheduler.load(ctx);
                for (int i = 0; i < stored.length(); i++) {
                    JSONObject s = stored.optJSONObject(i);
                    if (s != null) storedMap.put(s.optLong("id"), s);
                }
                for (int i = 0; i < wanted.length(); i++) {
                    JSONObject w = wanted.getJSONObject(i);
                    JSONObject s = storedMap.get(w.optLong("id"));
                    if (AlarmScheduler.wasDelivered(ctx, w.optLong("id"), w.optLong("at"))) continue;
                    boolean inActiveWindow = i < AlarmScheduler.MAX_ACTIVE_ALARMS;
                    boolean wasScheduled = s != null && s.optBoolean("scheduled", true);
                    if (inActiveWindow) {
                        if (s == null || !sameSpec(s, w) || !wasScheduled) {
                            // 用同一 id 重新 schedule 会覆盖旧的注册（PendingIntent 身份相同），
                            // 所以不需要先 cancel——这正是消除空窗的关键。
                            AlarmScheduler.schedule(ctx, w.optLong("id"), w.optLong("at"),
                                    w.optString("title", null), w.optString("body", null),
                                    w.optString("channel", null), w.optBoolean("vibrate", true),
                                    w.optBoolean("vibrateInSilent", true));
                            added++;
                        }
                        w.put("scheduled", true);
                    } else {
                        if (s != null && wasScheduled && w.optLong("at") > nowMs + 3000) {
                            AlarmScheduler.cancel(ctx, w.optLong("id"));
                            cancelled++;
                            wasScheduled = false;
                        }
                        w.put("scheduled", wasScheduled);
                        if (!wasScheduled) queued++;
                    }
                }
                // 再清理「期望列表里确实没有」的条目。
                for (Long id : storedMap.keySet()) {
                    if (wantedMap.containsKey(id)) continue;
                    JSONObject s = storedMap.get(id);
                    if (s == null || s.optBoolean("scheduled", true)) {
                        AlarmScheduler.cancel(ctx, id);
                        cancelled++;
                    }
                }
                // Persist both active alarms and their queued overflow.
                JSONArray persist = new JSONArray();
                for (int i = 0; i < wanted.length(); i++) {
                    JSONObject item = wanted.getJSONObject(i);
                    if (!AlarmScheduler.wasDelivered(ctx, item.optLong("id"), item.optLong("at"))) persist.put(item);
                }
                AlarmScheduler.save(ctx, persist);
            }
            // Scheduling does not start or keep a foreground service alive.

            JSObject ret = new JSObject();
            ret.put("added", added);
            ret.put("cancelled", cancelled);
            ret.put("kept", wanted.length() - added);
            ret.put("queued", queued);
            ret.put("nextAt", nextAt);
            call.resolve(ret);
            }
        } catch (Exception e) {
            call.reject("apply failed: " + e.getMessage());
        }
    }

    /** 测试提醒等单条排定：加入/替换并写入持久化列表 */
    @PluginMethod
    public void scheduleOne(PluginCall call) {
        try {
            Context ctx = getContext();
            JSONObject o = normalize(spec(call));
            if (o == null) { call.reject("id/at required"); return; }
            synchronized (AlarmScheduler.class) {
                AlarmScheduler.schedule(ctx, o.optLong("id"), o.optLong("at"),
                        o.optString("title", null), o.optString("body", null),
                        o.optString("channel", null), o.optBoolean("vibrate", true),
                        o.optBoolean("vibrateInSilent", true));
                o.put("scheduled", true);
                upsert(ctx, o);
            }
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("scheduleOne failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        try {
            Context ctx = getContext();
            Object idv = call.getData().get("id");
            if (!(idv instanceof Number)) { call.reject("id required"); return; }
            long id = ((Number) idv).longValue();
            synchronized (AlarmScheduler.class) {
                AlarmScheduler.cancel(ctx, id);
                AlarmScheduler.remove(ctx, id);
                AlarmScheduler.promoteQueued(ctx);
            }
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("cancel failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        try {
            Context ctx = getContext();
            synchronized (AlarmScheduler.class) {
                JSONArray stored = AlarmScheduler.load(ctx);
                for (int i = 0; i < stored.length(); i++) {
                    JSONObject s = stored.optJSONObject(i);
                    if (s != null) AlarmScheduler.cancel(ctx, s.optLong("id"));
                }
                AlarmScheduler.save(ctx, new JSONArray());
            }
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("cancelAll failed: " + e.getMessage());
        }
    }

    /** 诊断：已排定列表 + 最近触发时刻 + 系统已接受的最近闹钟 + 触发留痕 */
    @PluginMethod
    public void pending(PluginCall call) {
        try {
            Context ctx = getContext();
            JSONArray stored = AlarmScheduler.load(ctx);
            JSArray items = new JSArray();
            long nextAt = 0;
            int queued = 0;
            long now = System.currentTimeMillis();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject s = stored.optJSONObject(i);
                if (s == null) continue;
                if (!s.optBoolean("scheduled", true)) {
                    queued++;
                    long at = s.optLong("at");
                    if (at > now && (nextAt == 0 || at < nextAt)) nextAt = at;
                    continue;
                }
                JSObject item = new JSObject()
                        .put("id", s.optLong("id"))
                        .put("at", s.optLong("at"));
                items.put(item);
                long at = s.optLong("at");
                if (at > now && (nextAt == 0 || at < nextAt)) nextAt = at;
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            // 系统侧真实登记的闹钟时钟时刻（clock 模式下有值，降级模式下为 0）
            long sysNextAt = AlarmScheduler.nextClockAt(ctx);
            long[] lf = AlarmScheduler.lastFire(ctx);
            long[] lr = AlarmScheduler.lastReceiver(ctx);
            String schedMode = AlarmScheduler.lastSchedMode(ctx);
            JSObject ret = new JSObject();
            ret.put("items", items);
            ret.put("nextAt", nextAt);
            ret.put("queued", queued);
            ret.put("sysNextAt", sysNextAt);
            ret.put("schedMode", schedMode);
            ret.put("clockMode", "clock".equals(schedMode));
            // 原生已投递事件日志：JS 前台补弹前用它区分「原生已响」与「真的漏了」
            ret.put("deliveredLog", AlarmScheduler.deliveredLog(ctx));
            ret.put("lastFireId", lf[0]);
            ret.put("lastFireAt", lf[1]);
            ret.put("lastFireVia", AlarmScheduler.lastFireVia(ctx));
            android.content.SharedPreferences diagnostics = ctx.getSharedPreferences("ddlr_alarms", Context.MODE_PRIVATE);
            ret.put("lastExpectedAt", diagnostics.getLong("lastExpectedAt", 0));
            ret.put("lastDelayMs", diagnostics.getLong("lastDelayMs", -1));
            ret.put("lastNotificationAllowed", diagnostics.getBoolean("lastNotificationAllowed", false));
            String vibrationStatus = diagnostics.getString("lastVibrationStatus", "");
            ret.put("vibrationStatus", vibrationStatus);
            ret.put("lastVibrationStatus", vibrationStatus);
            ret.put("lastVibrationChannel", diagnostics.getString("lastVibrationChannel", ""));
            ret.put("lastVibrationUsage", diagnostics.getString("lastVibrationUsage", ""));
            ret.put("vibrateInSilent", call.getBoolean("vibrateInSilent", true));
            ret.put("lastReceiverId", lr[0]);
            ret.put("lastReceiverAt", lr[1]);
            ret.put("guard", AlarmService.isRunning());
            ret.put("wakeHeld", AlarmService.isWakeHeld());
            ret.put("sdk", Build.VERSION.SDK_INT);
            ret.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            android.content.pm.PackageInfo installed = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            ret.put("appVersion", installed.versionName);
            ret.put("appBuild", Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode);
            ret.put("androidRelease", Build.VERSION.RELEASE);
            ret.put("overlayAllowed", ReminderBanner.allowed(ctx));
            ret.put("trace", ReminderTrace.read(ctx));
            ret.put("environment", AppEnvironment.read(ctx));
            ret.put("lastDisplayChannel", diagnostics.getString("lastDisplayChannel", ""));
            if (diagnostics.contains("lastDeliveryAppVisible"))
                ret.put("lastDeliveryAppVisible", diagnostics.getBoolean("lastDeliveryAppVisible", false));
            JSObject displayState = new JSObject().put("appVisible", MainActivity.visible)
                    .put("serviceRunning", AlarmService.isRunning()).put("wakeHeld", AlarmService.isWakeHeld())
                    .put("overlayCards", ReminderBanner.visibleCount());
            try {
                android.os.PowerManager power = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (power != null) displayState.put("interactive", power.isInteractive());
            } catch (Exception ignored) { }
            ret.put("displayState", displayState);

            // ── 诊断增强：检查当前提醒模式实际使用的通知通道 ──
            // 通道重要性一旦被用户调低（或旧版建成了 LOW），App 改不回来，
            // 只能引导用户去系统设置。这里把真实重要性暴露给诊断面板。
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = normalizeChannelId(call.getString("channelId", "ddlr_high_sound"));
            boolean nativeMode = AlarmDelivery.nativeVibration(channelId);
            ret.put("foregroundChannelId", AlarmDelivery.notificationChannelFor(ctx, channelId));
            channelId = AlarmDelivery.backgroundNotificationChannelFor(ctx, channelId);
            int imp = AlarmService.channelImportance(nm, channelId);
            ret.put("channelId", channelId);
            ret.put("vibrationMode", nativeMode ? "native" : "notification");
            ret.put("channelImportance", imp);
            if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(channelId) != null) {
                ret.put("channelVibration", nm.getNotificationChannel(channelId).shouldVibrate());
            }
            // 4 = IMPORTANCE_HIGH，只有 >=4 才会弹 heads-up 顶部横幅
            ret.put("headsUp", imp >= 4 && androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled());
            // 全屏 Intent 权限（Android 14+ 可被用户关闭，关闭后锁屏不亮屏）
            if (Build.VERSION.SDK_INT >= 34 && nm != null) {
                try { ret.put("canFullScreen", nm.canUseFullScreenIntent()); }
                catch (Exception e) { ret.put("canFullScreen", true); }
            } else {
                ret.put("canFullScreen", true);
            }
            // 错过的提醒数：诊断面板只读，不清零
            // （清零由 JS 侧「启动补弹」显式消费，避免看一眼诊断就把计数吃掉）
            ret.put("missed", AlarmScheduler.getMissed(ctx, false));
            // 精确闹钟权限状态（Android 12+ 用户可撤销，撤销后只能降级）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                try { ret.put("exactOk", am.canScheduleExactAlarms()); }
                catch (Exception e) { ret.put("exactOk", true); }
            } else {
                ret.put("exactOk", true);
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("pending failed: " + e.getMessage());
        }
    }

    /* ---------------- 内部工具 ---------------- */

    private JSONObject spec(PluginCall call) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", (Number) call.getData().get("id"));
            o.put("at", (Number) call.getData().get("at"));
            o.put("title", call.getString("title", ""));
            o.put("body", call.getString("body", ""));
            o.put("channel", call.getString("channelId", "ddlr_high_sound"));
            o.put("vibrate", call.getBoolean("vibrate", true));
            o.put("vibrateInSilent", call.getBoolean("vibrateInSilent", true));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 规格化一条闹钟：id/at 必须为数字；通道字段兼容 JS 侧 channelId 与存储侧 channel */
    private JSONObject normalize(JSONObject in) {
        if (in == null) return null;
        Object idv = in.opt("id");
        Object atv = in.opt("at");
        if (!(idv instanceof Number) || !(atv instanceof Number)) return null;
        long id = ((Number) idv).longValue();
        long at = ((Number) atv).longValue();
        if (id < 0 || id >= Integer.MAX_VALUE || at <= 0) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("at", at);
            int repeatDays = in.optInt("repeatDays", 0);
            if (repeatDays != 0 && repeatDays != 1 && repeatDays != 7 && repeatDays != 14) return null;
            o.put("repeatDays", repeatDays);
            o.put("repeatUntil", Math.max(0, in.optLong("repeatUntil", 0)));
            o.put("v", SCHED_VER);
            o.put("title", in.optString("title", ""));
            o.put("body", in.optString("body", ""));
            String ch = in.optString("channel", "");
            if (ch.isEmpty()) ch = in.optString("channelId", "ddlr_high_sound");
            ch = AlarmDelivery.channelFor(ch, in.optBoolean("vibrate", true));
            o.put("channel", ch);
            o.put("vibrate", in.optBoolean("vibrate", true));
            o.put("vibrateInSilent", in.optBoolean("vibrateInSilent", true));
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 同 id 下比对规格版本、触发时刻与文案；任一变化都重排（更新 PendingIntent extras） */
    private boolean sameSpec(JSONObject a, JSONObject b) {
        return a.optInt("v", 1) == b.optInt("v", 1)
                && a.optInt("repeatDays", 0) == b.optInt("repeatDays", 0)
                && a.optLong("repeatUntil", 0) == b.optLong("repeatUntil", 0)
                && a.optLong("at") == b.optLong("at")
                && a.optString("title", "").equals(b.optString("title", ""))
                && a.optString("body", "").equals(b.optString("body", ""))
                && a.optString("channel", "").equals(b.optString("channel", ""))
                && a.optBoolean("vibrate", true) == b.optBoolean("vibrate", true)
                && a.optBoolean("vibrateInSilent", true) == b.optBoolean("vibrateInSilent", true);
    }

    private String normalizeChannelId(String id) {
        return AlarmDelivery.normalizeChannelId(id);
    }

    private void upsert(Context ctx, JSONObject o) {
        JSONArray arr = AlarmScheduler.load(ctx);
        JSONArray out = new JSONArray();
        long id = o.optLong("id");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null || s.optLong("id") == id) continue;
            out.put(s);
        }
        out.put(o);
        AlarmScheduler.save(ctx, out);
    }
}
