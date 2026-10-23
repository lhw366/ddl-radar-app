package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Host denies permission before instrumentation: revoking it mid-test kills the test process. */
@RunWith(AndroidJUnit4.class)
public class NotificationDeniedTest {
    @Test public void fallbackStartsForegroundBeforeRequestAndReleasesItsWakeLock() throws Exception {
        android.app.Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context context = instrument.getTargetContext();
        assertFalse(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled());
        assertFalse(ReminderBanner.allowed(context));
        context.startActivity(new Intent(context, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long at = System.currentTimeMillis() + 4000;
        JSONObject event = new JSONObject().put("id", 11004).put("at", at).put("title", "通知拒绝时兜底")
                .put("body", "有限前台服务").put("channel", "ddlr_high_v5_native_silent").put("vibrate", true)
                .put("scheduled", true).put("v", AlarmScheduler.SCHED_VER);
        AlarmScheduler.save(context, new JSONArray().put(event));
        AlarmScheduler.schedule(context, 11004, at, "通知拒绝时兜底", "有限前台服务", "ddlr_high_v5_native_silent", true);
        instrument.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
        boolean service = false, request = false;
        long end = System.currentTimeMillis() + 16000;
        while (System.currentTimeMillis() < end && !request) {
            JSONArray trace = ReminderTrace.read(context);
            for (int i = 0; i < trace.length(); i++) {
                JSONObject record = trace.getJSONObject(i);
                if (record.optLong("id") != 11004) continue;
                if ("service".equals(record.optString("stage"))) service = true;
                if ("direct_vibration_requested".equals(record.optString("stage"))
                        || "vibration_unavailable".equals(record.optString("stage"))) {
                    assertTrue("Fallback must run after service entered foreground", service);
                    request = true;
                }
            }
            Thread.sleep(100);
        }
        assertTrue("Missing foreground vibration fallback: " + ReminderTrace.read(context), request);
        Thread.sleep(5000);
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        AlarmScheduler.cancel(context, 11004);
    }
}
