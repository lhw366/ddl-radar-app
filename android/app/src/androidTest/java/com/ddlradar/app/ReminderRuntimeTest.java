package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReminderRuntimeTest {
    private Context ctx;
    private Instrumentation instrumentation;
    private RuntimeDevicePolicy devicePolicy;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        ctx = instrumentation.getTargetContext();
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell("pm grant " + ctx.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        ctx.startActivity(new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        ctx.stopService(new Intent(ctx, AlarmService.class));
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
    }

    @After public void cleanup() throws Exception {
        for (int id : new int[]{11001, 11002}) {
            AlarmScheduler.cancel(ctx, id);
            ReminderBanner.dismiss(AlarmDelivery.notificationId(id));
        }
        AlarmScheduler.save(ctx, new JSONArray());
        ctx.stopService(new Intent(ctx, AlarmService.class));
        instrumentation.waitForIdleSync();
        if (devicePolicy != null) devicePolicy.close();
    }

    @Test public void homeScreenAlarmStartsServiceAndDrawsNonzeroManualBanner() throws Exception {
        schedule(11001, 8000, false);
        assertFalse("Future events must not keep a service alive", AlarmService.isRunning());
        shell("input keyevent KEYCODE_HOME");
        awaitStage(11001, "overlay_visible", 16000);
        awaitCompositedBanner();
        assertTrue(hasStage(11001, "receiver"));
        assertTrue(hasStage(11001, "service"));
        assertTrue(hasStage(11001, "vibration_requested"));
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals("Avoid a system heads-up covering the custom banner", NotificationManager.IMPORTANCE_DEFAULT,
                manager.getNotificationChannel(AlarmDelivery.notificationChannelFor(ctx,
                        "ddlr_high_v4_vib_silent")).getImportance());
        screenshot("runtime-manual.png");
        Thread.sleep(6000);
        assertFalse("A retained banner must not retain a CPU wake lock", AlarmService.isWakeHeld());
        assertEquals("Manual banner must remain after five seconds", 1, visibleCards());
        ReminderBanner.dismiss(AlarmDelivery.notificationId(11001));
        instrumentation.waitForIdleSync();
        assertEquals(0, visibleCards());
        assertFalse(AlarmService.isWakeHeld());
        int deliveries = 0;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optLong("id") == 11001 && "delivery".equals(event.optString("stage"))) deliveries++;
        }
        assertEquals("Backup route must not repeat effects", 1, deliveries);
    }

    @Test public void automaticBannerDisappearsAfterFiveSeconds() throws Exception {
        schedule(11002, 4000, true);
        shell("input keyevent KEYCODE_HOME");
        awaitStage(11002, "overlay_visible", 12000);
        awaitCompositedBanner();
        assertEquals(1, visibleCards());
        Thread.sleep(6000);
        instrumentation.waitForIdleSync();
        assertEquals(0, visibleCards());
        assertFalse("Expired banners must stop their foreground service", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            assertNotEquals(AlarmDelivery.notificationId(11002), notification.getId());
        }
    }

    @Test public void realSavedGradientAndDeadlineDeliverWithoutOverlayPermission() throws Exception {
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        assertFalse(ReminderBanner.allowed(ctx));
        devicePolicy = new RuntimeDevicePolicy(ctx, instrumentation);
        devicePolicy.silentWithAllInterruptions();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long ready = System.currentTimeMillis() + 30000;
        while (!"true".equals(js(activity, "!!(window.S && window.NativeNotify && window.planReminders)"))) {
            assertTrue("Web app failed to load", System.currentTimeMillis() < ready);
            Thread.sleep(300);
        }
        // Actual five-minute tier and actual deadline, without shortening the
        // production gradient constants or bypassing save()/the Capacitor bridge.
        js(activity, "window.__saved='pending'; S.tasks=[{id:'runtime-gradient',name:'真实梯度验收',"
                + "dueAt:Date.now()+315000,tiers:{120:false,60:false,30:false,5:true},custom:[],"
                + "daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.tiered=true;S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;S.settings.vibrationMode='native';"
                + "S.settings.vibrateInSilent=true;S.settings.remindSilent=false;S.settings.bannerDuration='5s';"
                + "save().then(ok=>window.__saved=ok?'ready':'failed')");
        while (!"\"ready\"".equals(js(activity, "window.__saved"))) {
            assertTrue("Real event save was not acknowledged", System.currentTimeMillis() < ready);
            Thread.sleep(150);
        }
        JSONArray pending = AlarmScheduler.load(ctx);
        assertEquals("Exactly the enabled tier and the deadline", 2, pending.length());
        JSONObject tier = pending.getJSONObject(0), due = pending.getJSONObject(1);
        assertEquals(300000L, due.getLong("at") - tier.getLong("at"));
        String beforeTier = shellText("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(tier.getInt("id"), "direct_vibration_requested", 25000);
        awaitStage(tier.getInt("id"), "sound_suppressed", 4000);
        assertQuietNotification(tier.getInt("id"));
        awaitNewSystemVibration(beforeTier, true, "ALARM");
        assertFalse("Silent mode must never start the chime", hasStage(tier.getInt("id"), "sound_started"));
        assertTrue(stageDetail(tier.getInt("id"), "sound_suppressed").contains("reason=ringer_silent"));
        assertEquals("No overlay permission is required for system delivery", 0, visibleCards());
        assertTrue(hasStage(tier.getInt("id"), "direct_vibration_requested"));
        Thread.sleep(6000);
        assertFalse("Idle between tiers must not retain service", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        String beforeDeadline = shellText("dumpsys vibrator_manager");
        awaitStage(due.getInt("id"), "direct_vibration_requested", 315000);
        awaitStage(due.getInt("id"), "sound_suppressed", 4000);
        assertQuietNotification(due.getInt("id"));
        awaitNewSystemVibration(beforeDeadline, true, "ALARM");
        assertFalse("Silent mode must never start the chime", hasStage(due.getInt("id"), "sound_started"));
        assertTrue(stageDetail(due.getInt("id"), "sound_suppressed").contains("reason=ringer_silent"));
        assertEquals(android.media.AudioManager.RINGER_MODE_SILENT, devicePolicy.audio.getRingerMode());
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, devicePolicy.notifications.getCurrentInterruptionFilter());
        assertEquals(0, devicePolicy.audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION));
        for (JSONObject item : new JSONObject[]{tier, due}) {
            long delay = 0;
            int count = 0;
            JSONArray events = ReminderTrace.read(ctx);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.optLong("id") == item.getLong("id") && "delivery".equals(event.optString("stage"))) {
                    count++;
                    assertTrue("Delivery must happen while the app is in background",
                            event.optString("detail").contains("appVisible=false"));
                    delay = event.getLong("at") - item.getLong("at");
                }
            }
            assertEquals("Each normal occurrence executes once", 1, count);
            assertTrue("Normal reminder was late by " + delay, delay < 2500);
        }
        Thread.sleep(6000);
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    @Test public void realVibrationOnlyTaskDeliversWithoutOverlayOrForegroundService() throws Exception {
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long ready = System.currentTimeMillis() + 30000;
        while (!"true".equals(js(activity, "!!(window.S && window.NativeNotify && window.planReminders)"))) {
            assertTrue("Web app failed to load", System.currentTimeMillis() < ready);
            Thread.sleep(150);
        }
        js(activity, "window.__saved='pending'; S.tasks=[{id:'runtime-vibration-only',name:'纯震动后台任务',"
                + "dueAt:Date.now()+8000,tiers:{120:false,60:false,30:false,5:false},custom:[],"
                + "daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.sound=false;S.settings.vibrate=true;S.settings.banner=true;S.settings.vibrationMode='notification';"
                + "S.settings.remindSilent=false;S.settings.bannerDuration='5s';"
                + "save().then(ok=>window.__saved=ok?'ready':'failed')");
        while (!"\"ready\"".equals(js(activity, "window.__saved"))) {
            assertTrue("Real vibration-only task save was not acknowledged", System.currentTimeMillis() < ready);
            Thread.sleep(100);
        }
        JSONArray pending = AlarmScheduler.load(ctx);
        assertEquals(1, pending.length());
        int id = pending.getJSONObject(0).getInt("id");
        String before = shellText("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(id, "notification", 15000);
        assertSystemVibrationChannel(id);
        awaitNewSystemVibration(before, false);
        assertFalse("Vibration-only delivery must not start a service", hasStage(id, "service"));
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        assertFalse(hasStage(id, "direct_vibration_requested"));
    }

    private void assertSystemVibrationChannel(int id) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == AlarmDelivery.notificationId(id)) {
                android.app.NotificationChannel channel = manager.getNotificationChannel(active.getNotification().getChannelId());
                assertNotNull(channel);
                assertTrue("System notification must own background vibration", channel.shouldVibrate());
                assertArrayEquals(new long[]{0, 350, 150, 350, 150, 650}, channel.getVibrationPattern());
                return;
            }
        }
        fail("Real system notification missing for " + id);
    }

    @Test public void nativeVibrationOnlySavedTaskReleasesItsFiniteService() throws Exception {
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long ready = System.currentTimeMillis() + 30000;
        while (!"true".equals(js(activity, "!!(window.S && window.NativeNotify && window.planReminders)"))) {
            assertTrue(System.currentTimeMillis() < ready);
            Thread.sleep(150);
        }
        js(activity, "window.__saved='pending';S.tasks=[{id:'native-motor',name:'原生震动正常任务',"
                + "dueAt:Date.now()+8000,tiers:{120:false,60:false,30:false,5:false},custom:[],daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.sound=false;S.settings.vibrate=true;S.settings.banner=true;S.settings.vibrationMode='native';"
                + "S.settings.remindSilent=false;S.settings.bannerDuration='5s';save().then(ok=>window.__saved=ok?'ready':'failed')");
        while (!"\"ready\"".equals(js(activity, "window.__saved"))) {
            assertTrue(System.currentTimeMillis() < ready);
            Thread.sleep(100);
        }
        int id = AlarmScheduler.load(ctx).getJSONObject(0).getInt("id");
        String before = shellText("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(id, "direct_vibration_requested", 15000);
        assertNativeVibrationChannel(id);
        awaitNewSystemVibration(before, true);
        JSONArray trace = ReminderTrace.read(ctx);
        boolean foreground = false;
        for (int i = 0; i < trace.length(); i++) {
            JSONObject entry = trace.getJSONObject(i);
            if (entry.optLong("id") != id) continue;
            if ("service".equals(entry.optString("stage"))) foreground = true;
            if ("direct_vibration_requested".equals(entry.optString("stage"))) {
                assertTrue("Native vibration must follow foreground start", foreground);
                assertTrue(entry.optString("detail").contains("appVisible=false"));
            }
        }
        awaitStage(id, "wake_released", 4000);
        long stopped = System.currentTimeMillis() + 1500;
        while (AlarmService.isRunning() && System.currentTimeMillis() < stopped) Thread.sleep(50);
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    private void assertNativeVibrationChannel(int id) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() != AlarmDelivery.notificationId(id)) continue;
            assertTrue(AlarmDelivery.nativeVibration(active.getNotification().getChannelId()));
            assertFalse("Only native API owns vibration, avoiding duplicate effects",
                    manager.getNotificationChannel(active.getNotification().getChannelId()).shouldVibrate());
            assertNull("No sound URI which Android could demote to a second vibration",
                    manager.getNotificationChannel(active.getNotification().getChannelId()).getSound());
            return;
        }
        fail("Native route system notification missing for " + id);
    }

    private void assertQuietNotification(int id) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() != AlarmDelivery.notificationId(id)) continue;
            android.app.NotificationChannel channel = manager.getNotificationChannel(active.getNotification().getChannelId());
            assertEquals("ddlr_high_v6_quiet", channel.getId());
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.getImportance());
            assertFalse("Only the explicit silent haptic may vibrate", channel.shouldVibrate());
            assertNotNull("Silent banner eligibility uses a zero PCM cue", channel.getSound());
            assertTrue(channel.getSound().toString().endsWith("/ddlr_silence"));
            return;
        }
        fail("Silent reminder system notification missing for " + id);
    }

    @Test public void immediateNativeSelfCheckUsesTheRealForegroundBridge() throws Exception {
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long ready = System.currentTimeMillis() + 30000;
        while (!"true".equals(js(activity, "!!(window.S && window.NativeNotify)"))) {
            assertTrue(System.currentTimeMillis() < ready);
            Thread.sleep(100);
        }
        String before = shellText("dumpsys vibrator_manager");
        js(activity, "window.__nativeCheck={pending:true};Capacitor.Plugins.DdlNotify.testVibration()"
                + ".then(r=>window.__nativeCheck=r).catch(e=>window.__nativeCheck={error:String(e)})");
        while (!"true".equals(js(activity, "window.__nativeCheck.requested===true"))) {
            assertTrue("Native self-check failed: " + js(activity, "JSON.stringify(window.__nativeCheck)"),
                    System.currentTimeMillis() < ready);
            Thread.sleep(100);
        }
        awaitNewSystemVibration(before, true);
        assertTrue(hasStage(990000002, "direct_vibration_requested"));
        assertFalse("A foreground self-check needs no dispatch service", hasStage(990000002, "service"));
    }

    private void awaitNewSystemVibration(String before, boolean nativeProducer) throws Exception {
        awaitNewSystemVibration(before, nativeProducer, "NOTIFICATION");
    }

    private void awaitNewSystemVibration(String before, boolean nativeProducer, String usage) throws Exception {
        long until = System.currentTimeMillis() + 4000;
        String after = "";
        while (System.currentTimeMillis() < until) {
            after = shellText("dumpsys vibrator_manager");
            for (String line : after.split("\\n")) {
                boolean correctProducer = nativeProducer ? line.contains("| " + ctx.getPackageName() + " (uid=")
                        : line.contains("reason: Notification (" + ctx.getPackageName() + " ");
                if (correctProducer && line.contains("usage: " + usage + " |")
                        && line.contains("finished") && !before.contains(line.trim())) return;
            }
            Thread.sleep(150);
        }
        fail("No new completed system vibration for real reminder: " + after
                + "\nNotifications:\n" + shellText("dumpsys notification --noredact"));
    }

    private String shellText(String command) throws Exception {
        try (ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output);
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }

    @Test public void vibrationOffUsesSeparateSilentSystemChannel() {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String vibrate = AlarmDelivery.channelFor("ddlr_high_v3_silent", true);
        String still = AlarmDelivery.channelFor("ddlr_high_v3_silent", false);
        assertNotEquals(vibrate, still);
        AlarmDelivery.ensureChannel(ctx, manager, vibrate);
        AlarmDelivery.ensureChannel(ctx, manager, still);
        assertTrue(manager.getNotificationChannel(vibrate).shouldVibrate());
        assertFalse(manager.getNotificationChannel(still).shouldVibrate());
    }


    private String js(MainActivity activity, String script) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> output = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(script,
                value -> { output.set(value); completed.countDown(); }));
        assertTrue("JavaScript bridge timeout", completed.await(10, TimeUnit.SECONDS));
        return output.get();
    }

    @Test public void nativeRecurringOccurrencesAdvanceAndStopAtTheirBoundary() throws Exception {
        for (int days : new int[]{1, 7, 14}) {
            int id = 12000 + days;
            long at = System.currentTimeMillis() + 60000;
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTimeInMillis(at);
            calendar.add(java.util.Calendar.DAY_OF_YEAR, days);
            long nextAt = calendar.getTimeInMillis();
            JSONObject item = new JSONObject().put("id", id).put("at", at)
                    .put("title", "周期测试").put("body", "边界验证")
                    .put("channel", "ddlr_gentle_v3_silent").put("vibrate", false)
                    .put("repeatDays", days).put("repeatUntil", nextAt)
                    .put("scheduled", true).put("v", AlarmScheduler.SCHED_VER);
            try {
                AlarmScheduler.save(ctx, new JSONArray().put(item));
                AlarmScheduler.recordDelivered(ctx, id, at);
                AlarmScheduler.completeOccurrence(ctx, item);
                JSONObject next = AlarmScheduler.load(ctx).getJSONObject(0);
                assertEquals(nextAt, next.getLong("at"));
                assertTrue("Next occurrence must be registered with Android", next.getBoolean("scheduled"));
                assertNull("No occurrence beyond the boundary", AlarmScheduler.nextOccurrence(next, nextAt));
            } finally { AlarmScheduler.cancel(ctx, id); AlarmScheduler.save(ctx, new JSONArray()); }
        }
    }

    private int visibleCards() {
        AtomicInteger count = new AtomicInteger();
        instrumentation.runOnMainSync(() -> count.set(ReminderBanner.visibleCount()));
        return count.get();
    }

    private void schedule(int id, long delay, boolean autoDismiss) throws Exception {
        long at = System.currentTimeMillis() + delay;
        ctx.getSharedPreferences("ddlr_display", 0).edit().putBoolean("autoDismiss", autoDismiss).commit();
        JSONObject item = new JSONObject().put("id", id).put("at", at).put("title", "原生后台事件")
                .put("body", "此横幅应显示在桌面，不能是零高度")
                .put("channel", "ddlr_high_v2_silent").put("vibrate", true)
                .put("scheduled", true).put("v", AlarmScheduler.SCHED_VER);
        AlarmScheduler.save(ctx, new JSONArray().put(item));
        AlarmScheduler.schedule(ctx, id, at, item.getString("title"), item.getString("body"),
                item.getString("channel"), true);
    }

    private boolean hasStage(long id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optLong("id") == id && stage.equals(event.optString("stage"))) return true;
        }
        return false;
    }

    private String stageDetail(long id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optLong("id") == id && stage.equals(event.optString("stage"))) return event.optString("detail");
        }
        return "";
    }

    private void awaitStage(long id, String stage, long timeout) throws Exception {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until) {
            if (hasStage(id, stage)) return;
            Thread.sleep(100);
        }
        fail("Missing " + stage + ": " + ReminderTrace.read(ctx));
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }

    private void screenshot(String name) throws Exception {
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        screen.recycle();
    }

    private void awaitCompositedBanner() throws Exception {
        // A pre-draw callback/visible rectangle is not proof of screen pixels.
        // SystemUI's heads-up window has higher priority than app overlays.
        // A retained card must still be composited after that temporary cover.
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(200);
            Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
            int pixels = 0;
            if (screen != null) {
                for (int y = 0; y < screen.getHeight() / 2; y++) {
                    for (int x = 0; x < screen.getWidth(); x++) {
                        int color = screen.getPixel(x, y);
                        if (Math.abs(android.graphics.Color.red(color) - 35) <= 2
                                && Math.abs(android.graphics.Color.green(color) - 42) <= 2
                                && Math.abs(android.graphics.Color.blue(color) - 70) <= 2) pixels++;
                    }
                }
                screen.recycle();
            }
            if (pixels > 3000) return;
        }
        screenshot("missing-banner.png");
        fail("Overlay had a visible layout but its card was absent from the composited screen");
    }
}
