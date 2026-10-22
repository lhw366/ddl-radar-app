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

import java.util.HashMap;

/**
 * DDL雷达 原生系统闹钟桥（替代 Capacitor LocalNotifications 的调度职责）。
 * 调度统一走 AlarmManager.setAlarmClock()（见 AlarmScheduler）。
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
     * ——旧版已排定的闹钟必须强制重排，否则仍带着「共用 PendingIntent」的缺陷。
     */
    private static final int SCHED_VER = 4;

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
            JSONArray wanted = new JSONArray();
            HashMap<Long, JSONObject> wantedMap = new HashMap<>();
            long nextAt = 0;

            for (int i = 0; i < in.length(); i++) {
                JSONObject w = normalize(in.optJSONObject(i));
                if (w == null) continue;
                long id = w.optLong("id");
                if (wantedMap.containsKey(id)) continue; // id 去重
                wantedMap.put(id, w);
                wanted.put(w);
                long at = w.optLong("at");
                if (nextAt == 0 || at < nextAt) nextAt = at;
            }

            JSONArray stored = AlarmScheduler.load(ctx);
            HashMap<Long, JSONObject> storedMap = new HashMap<>();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject s = stored.optJSONObject(i);
                if (s != null) storedMap.put(s.optLong("id"), s);
            }

            int cancelled = 0, added = 0;
            long nowMs = System.currentTimeMillis();
            for (Long id : storedMap.keySet()) {
                JSONObject w = wantedMap.get(id);
                if (w == null) {
                    // 期望列表里没有：只有「仍然在未来」的才取消。
                    // 已到点（含即将到点）的条目必须保留——JS 侧 sync() 用
                    // `r.at <= now + 3000` 过滤掉临期提醒，若这里无条件 cancel，
                    // 就会把「马上要响」的闹钟撤掉，只能等应用内引擎 60 秒后补弹，
                    // 这正是用户反馈「延迟整整 1 分钟」的根因。
                    JSONObject s = storedMap.get(id);
                    long at = s == null ? 0 : s.optLong("at", 0);
                    if (at > nowMs + 3000) {
                        AlarmScheduler.cancel(ctx, id);
                        cancelled++;
                    }
                    continue;
                }
                if (!sameSpec(storedMap.get(id), w)) {
                    AlarmScheduler.cancel(ctx, id);
                    cancelled++;
                }
            }
            for (int i = 0; i < wanted.length(); i++) {
                JSONObject w = wanted.getJSONObject(i);
                JSONObject s = storedMap.get(w.optLong("id"));
                if (s == null || !sameSpec(s, w)) {
                    AlarmScheduler.schedule(ctx, w.optLong("id"), w.optLong("at"),
                            w.optString("title", null), w.optString("body", null),
                            w.optString("channel", null));
                    added++;
                }
            }
            // 持久化列表 = 期望列表 ∪ 仍未被投递的临期条目。
            // 只存 wanted 会把上面刻意保留的临期闹钟从存储里抹掉，
            // 导致它虽仍注册在系统里，但诊断面板看不到、重启后也无法恢复。
            // 注意保留范围：只留「最近 2 分钟内到点」的——那是正在投递中的，
            // 更早的说明已经响过或彻底错过，留着会让列表无限增长。
            JSONArray persist = new JSONArray();
            for (int i = 0; i < wanted.length(); i++) persist.put(wanted.getJSONObject(i));
            for (Long id : storedMap.keySet()) {
                if (wantedMap.containsKey(id)) continue;
                JSONObject s = storedMap.get(id);
                if (s == null) continue;
                long at = s.optLong("at", 0);
                if (at > nowMs + 3000) continue;          // 未来条目：该取消的已在上面处理
                if (at < nowMs - 120000) continue;        // 早于 2 分钟：视为已处理，丢弃
                persist.put(s);                            // 投递窗口内：保留
            }
            AlarmScheduler.save(ctx, persist);
            AlarmService.start(ctx); // 守护服务刷新「下一条」显示（并确保常驻）

            JSObject ret = new JSObject();
            ret.put("added", added);
            ret.put("cancelled", cancelled);
            ret.put("kept", wanted.length() - added);
            ret.put("nextAt", nextAt);
            call.resolve(ret);
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
            AlarmScheduler.schedule(ctx, o.optLong("id"), o.optLong("at"),
                    o.optString("title", null), o.optString("body", null),
                    o.optString("channel", null));
            upsert(ctx, o);
            AlarmService.start(ctx);
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
            AlarmScheduler.cancel(ctx, id);
            AlarmScheduler.remove(ctx, id);
            AlarmService.start(ctx);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("cancel failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        try {
            Context ctx = getContext();
            JSONArray stored = AlarmScheduler.load(ctx);
            for (int i = 0; i < stored.length(); i++) {
                JSONObject s = stored.optJSONObject(i);
                if (s != null) AlarmScheduler.cancel(ctx, s.optLong("id"));
            }
            AlarmScheduler.save(ctx, new JSONArray());
            AlarmService.start(ctx);
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
            long now = System.currentTimeMillis();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject s = stored.optJSONObject(i);
                if (s == null) continue;
                JSObject item = new JSObject()
                        .put("id", s.optLong("id"))
                        .put("at", s.optLong("at"));
                items.put(item);
                long at = s.optLong("at");
                if (at > now && (nextAt == 0 || at < nextAt)) nextAt = at;
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            long sysNextAt = 0;
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
                if (info != null) sysNextAt = info.getTriggerTime();
            }
            long[] lf = AlarmScheduler.lastFire(ctx);
            JSObject ret = new JSObject();
            ret.put("items", items);
            ret.put("nextAt", nextAt);
            ret.put("sysNextAt", sysNextAt);
            ret.put("lastFireId", lf[0]);
            ret.put("lastFireAt", lf[1]);
            ret.put("lastFireVia", AlarmScheduler.lastFireVia(ctx));
            ret.put("guard", AlarmService.isRunning());
            ret.put("sdk", Build.VERSION.SDK_INT);

            // ── 诊断增强：解释「为什么没弹横幅」 ──
            // 通道重要性一旦被用户调低（或旧版建成了 LOW），App 改不回来，
            // 只能引导用户去系统设置。这里把真实重要性暴露给诊断面板。
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            int imp = AlarmService.channelImportance(nm, "ddlr_full");
            ret.put("channelImportance", imp);
            // 4 = IMPORTANCE_HIGH，只有 >=4 才会弹 heads-up 顶部横幅
            ret.put("headsUp", imp >= 4);
            // 全屏 Intent 权限（Android 14+ 可被用户关闭，关闭后锁屏不亮屏）
            if (Build.VERSION.SDK_INT >= 34 && nm != null) {
                try { ret.put("canFullScreen", nm.canUseFullScreenIntent()); }
                catch (Exception e) { ret.put("canFullScreen", true); }
            } else {
                ret.put("canFullScreen", true);
            }
            // 错过的提醒数（重启/关机期间错过的）
            ret.put("missed", AlarmScheduler.takeMissed(ctx));
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
            o.put("channel", call.getString("channelId", "ddlr_full"));
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
        try {
            JSONObject o = new JSONObject();
            o.put("id", ((Number) idv).longValue());
            o.put("at", ((Number) atv).longValue());
            o.put("v", SCHED_VER);
            o.put("title", in.optString("title", ""));
            o.put("body", in.optString("body", ""));
            String ch = in.optString("channel", "");
            if (ch.isEmpty()) ch = in.optString("channelId", "ddlr_full");
            o.put("channel", ch);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 同 id 下比对规格版本、触发时刻与文案；任一变化都重排（更新 PendingIntent extras） */
    private boolean sameSpec(JSONObject a, JSONObject b) {
        return a.optInt("v", 1) == b.optInt("v", 1)
                && a.optLong("at") == b.optLong("at")
                && a.optString("title", "").equals(b.optString("title", ""))
                && a.optString("body", "").equals(b.optString("body", ""))
                && a.optString("channel", "").equals(b.optString("channel", ""));
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
