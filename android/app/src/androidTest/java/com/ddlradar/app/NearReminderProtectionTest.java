package com.ddlradar.app;

import android.app.AlarmManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real save/bridge, HOME, and a withheld system callback. No product test switches. */
@RunWith(AndroidJUnit4.class)
public class NearReminderProtectionTest {
    private final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    private final Context ctx = instrument.getTargetContext();
    private RuntimeDevicePolicy policy;
    private MainActivity activity;

    private String js(String expression) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrument.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(expression,
                value -> { result.set(value); ready.countDown(); }));
        assertTrue("WebView callback timed out", ready.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    @Before public void open() throws Exception {
        cleanupSchedule();
        activity = (MainActivity) instrument.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end = System.currentTimeMillis() + 30000;
        while (!"true".equals(js("!!(window.S && window.NativeNotify && window.planReminders)"))) {
            assertTrue("App failed to initialize", System.currentTimeMillis() < end);
            Thread.sleep(100);
        }
    }

    private JSONObject saveTask(String name, long at, boolean effects) throws Exception {
        js("window.__nearSaved='pending'; S.tasks=[{id:'" + name + "',name:'普通事项',dueAt:" + at + ","
                + "tiers:{120:false,60:false,30:false,5:false},custom:[],daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.sound=" + effects + ";S.settings.vibrate=" + effects + ";S.settings.banner=true;"
                + "S.settings.vibrationMode='native';S.settings.vibrateInSilent=true;S.settings.remindSilent=false;"
                + "S.settings.bannerDuration='5s';save().then(ok=>window.__nearSaved=ok?'ready':'failed')");
        long end = System.currentTimeMillis() + 10000;
        while (!"\"ready\"".equals(js("window.__nearSaved"))) {
            assertTrue("Save was not acknowledged", System.currentTimeMillis() < end);
            Thread.sleep(50);
        }
        JSONArray items = AlarmScheduler.load(ctx);
        assertEquals(1, items.length());
        return items.getJSONObject(0);
    }

    @Test public void normalTaskDeliversAtHomeEvenWhenSystemCallbackIsWithheld() throws Exception {
        policy = new RuntimeDevicePolicy(ctx, instrument);
        policy.silentWithAllInterruptions();
        long at = System.currentTimeMillis() + 8000;
        JSONObject task = saveTask("protected-real-task", at, true);
        int id = task.getInt("id");
        assertTrue("Save must wait for actual finite preparation", AlarmService.isRunning());
        assertTrue(AlarmService.protectionState().getBoolean("waiting"));
        assertFalse("Waiting must not hold a CPU lock", AlarmService.isWakeHeld());
        // Fault injection only: keep registration, but withhold Android's alarm
        // callback, reproducing an unavailable/delayed system delivery entry.
        ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(
                AlarmScheduler.operation(ctx, id, at, task.optString("title"), task.optString("body"),
                        task.optString("channel"), true, 0));
        shell("input keyevent KEYCODE_HOME");
        awaitBackground();
        assertFalse(MainActivity.visible);
        assertFalse(AlarmService.isWakeHeld());
        await(id, "overlay_visible", 14000);
        await(id, "sound_suppressed", 3000);
        assertEquals(1, count(id, "notification"));
        assertEquals(1, count(id, "direct_vibration_requested"));
        assertEquals(0, count(id, "sound_started"));
        assertEquals("protected_timer", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastFireVia", ""));
        JSONObject delivered = stage(id, "delivery");
        long delay = delivered.getLong("at") - at;
        assertTrue("Protected task late by " + delay, delay >= 0 && delay < 2500);
        assertTrue(delivered.optString("detail").contains("appVisible=false"));
        awaitIdle(8000);
        // The delayed system copy must not replay any reminder effects.
        ctx.startForegroundService(new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_DUE)
                .putExtra("id", id).putExtra("at", at));
        await(id, "delivery_skipped", 2500);
        awaitIdle(3000);
        assertEquals(1, count(id, "notification"));
        assertEquals(1, count(id, "direct_vibration_requested"));
        JSONObject evidence = new JSONObject().put("systemCallbackWithheld", true).put("expectedAt", at)
                .put("protectionStartedAt", stage(id, "protection_started").getLong("at"))
                .put("backgroundAt", ctx.getSharedPreferences("ddlr_lifecycle", 0).getLong("lastBackgroundAt", 0))
                .put("deliveredAt", delivered.getLong("at")).put("delayMs", delay)
                .put("waitingWakeHeld", false).put("appVisible", false).put("trace", ReminderTrace.read(ctx));
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "near-protection-runtime.json"))) {
            out.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void editingBeyondTheFiniteWindowStopsProtectionAndKeepsTheFutureAlarm() throws Exception {
        saveTask("edited-real-task", System.currentTimeMillis() + 30000, false);
        assertTrue(AlarmService.protectionState().getBoolean("waiting"));
        JSONObject later = saveTask("edited-real-task", System.currentTimeMillis() + 600000, false);
        awaitIdle(3000);
        assertTrue("The far-future occurrence must remain registered", AlarmScheduler.isRegistered(ctx,
                later.getLong("id"), later.getLong("at")));
        assertFalse(AlarmService.protectionState().getBoolean("waiting"));
    }

    @Test public void farFutureTaskDoesNotStartAnIdleForegroundServiceOrCpuLock() throws Exception {
        saveTask("far-real-task", System.currentTimeMillis() + 600000, false);
        shell("input keyevent KEYCODE_HOME");
        awaitBackground();
        assertFalse(MainActivity.visible);
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        assertEquals(1, AlarmScheduler.load(ctx).length());
    }

    private JSONObject stage(int id, String name) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject entry = trace.getJSONObject(i);
            if (entry.optInt("id") == id && name.equals(entry.optString("stage"))) return entry;
        }
        return null;
    }

    private int count(int id, String name) throws Exception {
        int count = 0;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject entry = trace.getJSONObject(i);
            if (entry.optInt("id") == id && name.equals(entry.optString("stage"))) count++;
        }
        return count;
    }

    private void await(int id, String name, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (stage(id, name) == null && System.currentTimeMillis() < end) Thread.sleep(50);
        assertNotNull("Missing " + name + ": " + ReminderTrace.read(ctx), stage(id, name));
    }

    private void awaitIdle(long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (AlarmService.isRunning() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertFalse("Protection/effects must exit when no near occurrence remains", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    private void awaitBackground() throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (MainActivity.visible && System.currentTimeMillis() < end) Thread.sleep(25);
        assertFalse("HOME must finish before deadline observation", MainActivity.visible);
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrument.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = new byte[4096];
            while (input.read(bytes) != -1) { }
        }
    }

    private void cleanupSchedule() throws Exception {
        JSONArray items = AlarmScheduler.load(ctx);
        for (int i = 0; i < items.length(); i++) AlarmScheduler.cancel(ctx, items.getJSONObject(i).getLong("id"));
        AlarmScheduler.save(ctx, new JSONArray());
        ctx.stopService(new Intent(ctx, AlarmService.class));
        awaitIdle(3000);
    }

    @After public void close() throws Exception {
        cleanupSchedule();
        ((android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
        if (policy != null) policy.close();
    }
}
