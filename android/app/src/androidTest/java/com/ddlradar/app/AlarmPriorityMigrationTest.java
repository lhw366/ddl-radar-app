package com.ddlradar.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AlarmPriorityMigrationTest {
    @Test public void upgradeRetiresOldTokenAndSystemDeliversUrgentBroadcastOnce() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int id = 11415;
        long at = System.currentTimeMillis() + 4000;
        Intent old = new Intent(ctx, AlarmReceiver.class)
                .setAction("com.ddlradar.app.DELIVER_REMINDER")
                .setData(Uri.parse("ddlr://alarm/" + id + "/0"))
                .putExtra("id", id).putExtra("at", at);
        PendingIntent legacy = PendingIntent.getBroadcast(ctx, 0, old,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at, null), legacy);
        JSONObject event = new JSONObject().put("id", id).put("at", at).put("v", 14)
                .put("title", "升级迁移").put("body", "只投递一次")
                .put("channel", "ddlr_gentle_v6_quiet").put("vibrate", false).put("scheduled", true);
        AlarmScheduler.save(ctx, new JSONArray().put(event));
        AlarmScheduler.reapplyAll(ctx);
        assertEquals(15, AlarmScheduler.load(ctx).getJSONObject(0).getInt("v"));
        try { legacy.send(); fail("The old background-priority PendingIntent must be cancelled"); }
        catch (PendingIntent.CanceledException expected) { }
        long end = System.currentTimeMillis() + 10000;
        while (!AlarmScheduler.wasDelivered(ctx, id, at) && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(AlarmScheduler.wasDelivered(ctx, id, at));
        Thread.sleep(300);
        int receivers = 0;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject entry = trace.getJSONObject(i);
            if (entry.optLong("id") == id && entry.optString("stage").equals("receiver")) {
                receivers++;
                assertTrue("Check the ACTUAL received flags, not the factory source",
                        entry.optString("detail").contains("urgent=true"));
            }
        }
        assertEquals(1, receivers);
        AlarmScheduler.cancel(ctx, id);
        assertFalse(AlarmService.isRunning());
    }
}
