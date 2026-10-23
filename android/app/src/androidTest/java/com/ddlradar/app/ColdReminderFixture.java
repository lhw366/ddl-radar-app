package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The host ends instrumentation and kills the process before this alarm fires. */
@RunWith(AndroidJUnit4.class)
public class ColdReminderFixture {
    @Test public void scheduleForHostProcessDeathTest() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.stopService(new Intent(ctx, AlarmService.class));
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
        ctx.getSharedPreferences("ddlr_display", 0).edit().putBoolean("autoDismiss", false).commit();
        long at = System.currentTimeMillis() + 30000;
        JSONObject item = new JSONObject().put("id", 11003).put("at", at)
                .put("title", "进程结束后的提醒").put("body", "必须由系统闹钟重新启动服务")
                .put("channel", "ddlr_high_v5_native_silent").put("vibrate", true)
                .put("scheduled", true).put("v", AlarmScheduler.SCHED_VER);
        AlarmScheduler.save(ctx, new JSONArray().put(item));
        AlarmScheduler.schedule(ctx, 11003, at, item.getString("title"), item.getString("body"),
                item.getString("channel"), true);
    }
}
