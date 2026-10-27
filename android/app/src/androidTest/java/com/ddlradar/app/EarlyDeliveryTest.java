package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Inject the firmware's premature callback at the actual service boundary. */
@RunWith(AndroidJUnit4.class)
public class EarlyDeliveryTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context ctx = instrumentation.getTargetContext();
    private static final String CHANNEL = "ddlr_gentle_v4_still_silent";

    private JSONObject schedule(int id, long at) throws Exception {
        JSONObject item = new JSONObject().put("id", id).put("at", at).put("v", AlarmScheduler.SCHED_VER)
                .put("title", "普通截止提醒").put("body", "提前回调不应吞掉真正的截止")
                .put("channel", CHANNEL).put("vibrate", false).put("scheduled", true);
        AlarmScheduler.save(ctx, new JSONArray().put(item));
        AlarmScheduler.schedule(ctx, id, at, item.getString("title"), item.getString("body"), CHANNEL, false);
        return item;
    }

    private PendingIntent token(int id, long at) {
        return AlarmScheduler.operation(ctx, id, at, "普通截止提醒", "提前回调不应吞掉真正的截止", CHANNEL, false, 0);
    }

    @Test public void prematureDeadlineWaitsWithoutCpuLockAndSurvivesADelayedSystemCallback() throws Exception {
        int id = 11640;
        long at = System.currentTimeMillis() + 6500;
        schedule(id, at);
        token(id, at).send(); // Fault: consume/start the due service before its real deadline.
        await(id, "early_rearm_rearmed", 2500);
        assertFalse(AlarmScheduler.wasDelivered(ctx, id, at));
        assertEquals(0, count(id, "notification"));
        assertEquals("clock_shift_compensation",
                ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastEarlyMode", ""));
        assertTrue("The replacement must stay on the wake-capable AlarmClock route",
                ctx.getSharedPreferences("ddlr_alarms", 0).getLong("lastEarlyRearmAt", 0)
                        > at + AlarmScheduler.EARLY_REARM_SAFETY_MS);
        awaitWaitingWithoutWake();
        assertTrue("The future occurrence must remain persisted", AlarmScheduler.findOccurrence(ctx, id, at) != null);
        assertTrue(AlarmScheduler.isRegistered(ctx, id, at));
        // Model the ROM delaying the replacement callback. The already-running
        // finite service must reach the deadline without reopening the UI.
        ((android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(token(id, at));
        await(id, "notification", 9000);
        awaitIdle();
        assertTrue(AlarmScheduler.wasDelivered(ctx, id, at));
        assertEquals(1, count(id, "notification"));
        assertEquals(1, count(id, "early_rearm_rearmed"));
        assertEquals(2, count(id, "alarm_entry"));
        assertEquals("protected_timer", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastFireVia", ""));
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") == id && "notification".equals(event.optString("stage")))
                assertTrue("No notification before the intended deadline", event.getLong("at") >= at);
        }
        awaitIdle();
    }

    @Test public void aSecondPrematureCallbackCannotCreateARetryStormOrExtendTheWaitingLimit() throws Exception {
        int id = 11641;
        long at = System.currentTimeMillis() + 30000;
        schedule(id, at);
        token(id, at).send();
        await(id, "early_rearm_rearmed", 2500);
        awaitWaitingWithoutWake();
        long expires = AlarmService.protectionState().getLong("expiresElapsed");
        token(id, at).send();
        await(id, "early_rearm_exhausted", 2500);
        awaitWaitingWithoutWake();
        assertEquals(expires, AlarmService.protectionState().getLong("expiresElapsed"));
        assertEquals(1, count(id, "early_rearm_rearmed"));
        assertEquals(1, count(id, "early_rearm_exhausted"));
        assertEquals(0, count(id, "notification"));
        assertFalse(AlarmScheduler.wasDelivered(ctx, id, at));
    }

    @Test public void editingDeadlineCancelsOldTokenAndQueuedOldExtrasCannotDeliverOrRearm() throws Exception {
        int id = 11642;
        long oldAt = System.currentTimeMillis() + 3000;
        schedule(id, oldAt);
        PendingIntent old = token(id, oldAt);
        long newAt = System.currentTimeMillis() + 6500;
        schedule(id, newAt);
        PendingIntent replacement = token(id, newAt);
        assertNotEquals("Every occurrence has its own immutable identity", old, replacement);
        try { old.send(); fail("The superseded token must be cancelled"); }
        catch (PendingIntent.CanceledException expected) { }
        // Model a copy already queued by Android before cancellation.
        ctx.startForegroundService(new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_DUE)
                .putExtra("id", id).putExtra("at", oldAt).putExtra("channelId", CHANNEL));
        await(id, "delivery_stale", 2500);
        assertEquals(0, count(id, "notification"));
        assertEquals(0, count(id, "early_rearm_rearmed"));
        assertEquals(newAt, AlarmScheduler.findOccurrence(ctx, id, newAt).getLong("at"));
        await(id, "notification", 10000);
        awaitIdle();
        assertTrue(AlarmScheduler.wasDelivered(ctx, id, newAt));
        assertEquals(1, count(id, "notification"));
        awaitIdle();
    }

    @Test public void cancelledFutureCallbackCannotResurrectTheRemovedTask() throws Exception {
        int id = 11643;
        long at = System.currentTimeMillis() + 30000;
        schedule(id, at);
        PendingIntent original = token(id, at);
        AlarmScheduler.cancel(ctx, id);
        AlarmScheduler.remove(ctx, id);
        try { original.send(); fail("Cancelled token must be invalid"); }
        catch (PendingIntent.CanceledException expected) { }
        ctx.startForegroundService(new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_DUE)
                .putExtra("id", id).putExtra("at", at).putExtra("channelId", CHANNEL));
        await(id, "delivery_stale", 2500);
        assertEquals(0, count(id, "notification"));
        assertEquals(0, count(id, "early_rearm_rearmed"));
        assertFalse(AlarmScheduler.isRegistered(ctx, id, at));
        awaitIdle();
    }

    private int count(int id, String stage) throws Exception {
        int count = 0;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") == id && stage.equals(event.optString("stage"))) count++;
        }
        return count;
    }

    private void await(int id, String stage, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (count(id, stage) == 0 && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue("Missing " + stage + ": " + ReminderTrace.read(ctx), count(id, stage) > 0);
    }

    private void awaitIdle() throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (AlarmService.isRunning() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertFalse("Premature callbacks must not leave a waiting service", AlarmService.isRunning());
        assertFalse("Premature callbacks must not retain a CPU lock", AlarmService.isWakeHeld());
    }

    private void awaitWaitingWithoutWake() throws Exception {
        long end = System.currentTimeMillis() + 2000;
        while (AlarmService.isWakeHeld() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue(AlarmService.isRunning());
        assertTrue(AlarmService.protectionState().getBoolean("waiting"));
        assertFalse("No CPU lock while waiting for a future reminder", AlarmService.isWakeHeld());
    }

    @After public void cleanup() {
        for (int id = 11640; id <= 11643; id++) {
            AlarmScheduler.cancel(ctx, id);
            AlarmDelivery.cancelNotification(ctx, AlarmDelivery.notificationId(id));
        }
        AlarmScheduler.save(ctx, new JSONArray());
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }
}
