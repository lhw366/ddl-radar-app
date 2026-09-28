package com.ddlradar.app;

import android.app.AlarmManager;
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

    /** 闹钟注册规格版本：变更后 first apply 全量重排（v2 = 双机制注册 + 接收器直执行声震） */
    private static final int SCHED_VER = 2;

    @PluginMethod
    public void apply(PluginCall call) {
        try {
            Context ctx = getContext();
            JSArray in = call.getArray("alarms");
            JSONArray wanted = new JSONArray();
            HashMap<Long, JSONObject> wantedMap = new HashMap<>();
            long nextAt = 0;

            if (in != null) {
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
            }

            JSONArray stored = AlarmScheduler.load(ctx);
            HashMap<Long, JSONObject> storedMap = new HashMap<>();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject s = stored.optJSONObject(i);
                if (s != null) storedMap.put(s.optLong("id"), s);
            }

            int cancelled = 0, added = 0;
            for (Long id : storedMap.keySet()) {
                JSONObject w = wantedMap.get(id);
                if (w == null || !sameSpec(storedMap.get(id), w)) {
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
            AlarmScheduler.save(ctx, wanted);

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
            ret.put("sdk", Build.VERSION.SDK_INT);
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
