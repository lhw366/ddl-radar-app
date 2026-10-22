package com.ddlradar.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, on-device evidence of delivery stages; no task titles or bodies. */
final class ReminderTrace {
    static final String PREFS = "ddlr_trace";

    static synchronized void mark(Context context, long id, String stage, String detail) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(prefs.getString("events", "[]"));
            JSONArray events = new JSONArray();
            for (int i = Math.max(0, old.length() - 59); i < old.length(); i++) events.put(old.get(i));
            JSONObject event = new JSONObject().put("id", id).put("at", System.currentTimeMillis())
                    .put("pid", android.os.Process.myPid())
                    .put("stage", stage).put("detail", detail == null ? "" : detail);
            events.put(event);
            prefs.edit().putString("events", events.toString()).commit();
            Log.i("DdlTrace", event.toString());
        } catch (Exception error) { Log.w("DdlTrace", "Cannot record delivery stage", error); }
    }

    static JSONArray read(Context context) {
        try {
            return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]"));
        } catch (Exception error) { return new JSONArray(); }
    }
}
