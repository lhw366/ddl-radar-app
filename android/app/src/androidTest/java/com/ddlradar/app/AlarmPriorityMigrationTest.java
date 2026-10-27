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
    private final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void migratePlainBroadcast() throws Exception { migrate(14, ""); }
    @Test public void migrateUrgentBroadcastFromBuild96() throws Exception { migrate(15, "/urgent"); }

    @Test public void migrateBuild102ServiceTokenWithoutReusingItsMutableOccurrence() throws Exception {
        int id = 11416;
        long at = System.currentTimeMillis() + 4000;
        Intent intent = new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_DUE)
                .setData(Uri.parse("ddlr://alarm/" + id + "/0/service"))
                .putExtra("id", id).putExtra("at", at);
        PendingIntent old = PendingIntent.getForegroundService(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).setAlarmClock(
                new AlarmManager.AlarmClockInfo(at, null), old);
        AlarmScheduler.save(ctx, new JSONArray().put(new JSONObject().put("id", id).put("at", at)
                .put("v", 16).put("channel", "ddlr_gentle_v4_still_silent").put("vibrate", false)));
        try {
            AlarmScheduler.reapplyAll(ctx);
            try { old.send(); fail("Build 102 token must be retired"); }
            catch (PendingIntent.CanceledException expected) { }
            awaitDelivery(id, at);
            awaitIdle();
            assertEquals(1, count(id, "notification"));
            assertTrue(AlarmScheduler.load(ctx).length() == 0);
        } finally { AlarmScheduler.cancel(ctx, id); }
    }

    private void migrate(int version, String suffix) throws Exception {
        int id = 11400 + version;
        long at = System.currentTimeMillis() + 4000;
        Intent old = new Intent(ctx, AlarmReceiver.class)
                .setAction("com.ddlradar.app.DELIVER_REMINDER")
                .setData(Uri.parse("ddlr://alarm/" + id + "/0" + suffix))
                .putExtra("id", id).putExtra("at", at);
        if (version == 15) old.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent legacy = PendingIntent.getBroadcast(ctx, 0, old,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at, null), legacy);
        JSONObject event = new JSONObject().put("id", id).put("at", at).put("v", version)
                .put("title", "升级迁移").put("body", "只投递一次")
                .put("channel", "ddlr_gentle_v4_still_silent").put("vibrate", false).put("scheduled", true);
        AlarmScheduler.save(ctx, new JSONArray().put(event));
        try {
            AlarmScheduler.reapplyAll(ctx);
            assertEquals(AlarmScheduler.SCHED_VER, AlarmScheduler.load(ctx).getJSONObject(0).getInt("v"));
            PendingIntent replacement = AlarmScheduler.operation(ctx, id, at, "升级迁移", "只投递一次",
                    "ddlr_gentle_v4_still_silent", false, 0);
            assertTrue("The system token must target a foreground service", replacement.isForegroundService());
            try { legacy.send(); fail("The old broadcast token must be cancelled"); }
            catch (PendingIntent.CanceledException expected) { }
            awaitDelivery(id, at);
            awaitIdle();
            assertEquals(1, count(id, "alarm_entry"));
            assertEquals(0, count(id, "receiver"));
            assertEquals(1, count(id, "notification"));
            assertEquals("foreground_service", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastEntryRoute", ""));
        } finally { AlarmScheduler.cancel(ctx, id); }
    }

    @Test public void duplicateServiceStartCannotReplayNativeEffects() throws Exception {
        int id = 11618;
        long at = System.currentTimeMillis() + 2000;
        AlarmScheduler.schedule(ctx, id, at, "一次提醒", "不可重复震动", "ddlr_gentle_v5_native_silent", true);
        try {
            awaitDelivery(id, at);
            long end = System.currentTimeMillis() + 3000;
            while (count(id, "direct_vibration_requested") == 0 && System.currentTimeMillis() < end) Thread.sleep(25);
            assertEquals(1, count(id, "direct_vibration_requested"));
            ctx.startForegroundService(new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_DUE)
                    .putExtra("id", id).putExtra("at", at).putExtra("channelId", "ddlr_gentle_v5_native_silent")
                    .putExtra("vibrate", true).putExtra("directVibration", true));
            Thread.sleep(300);
            awaitIdle();
            assertEquals(1, count(id, "notification"));
            assertEquals(1, count(id, "direct_vibration_requested"));
            assertEquals(1, count(id, "delivery_skipped"));
        } finally { AlarmScheduler.cancel(ctx, id); }
    }

    @Test public void cancelledServiceAlarmNeverStarts() throws Exception {
        int id = 11619;
        long at = System.currentTimeMillis() + 2000;
        AlarmScheduler.schedule(ctx, id, at, "已取消", "不可投递", "ddlr_gentle_v4_still_silent", false);
        AlarmScheduler.cancel(ctx, id);
        Thread.sleep(3000);
        assertFalse(AlarmScheduler.wasDelivered(ctx, id, at));
        assertEquals(0, count(id, "alarm_entry"));
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    private int count(int id, String stage) throws Exception {
        int n = 0;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject entry = trace.getJSONObject(i);
            if (entry.optLong("id") == id && stage.equals(entry.optString("stage"))) n++;
        }
        return n;
    }
    private void awaitDelivery(int id, long at) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (!AlarmScheduler.wasDelivered(ctx, id, at) && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue(AlarmScheduler.wasDelivered(ctx, id, at));
    }
    private void awaitIdle() throws Exception {
        long end = System.currentTimeMillis() + 4000;
        while (AlarmService.isRunning() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertFalse("The finite service must exit", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }
}
