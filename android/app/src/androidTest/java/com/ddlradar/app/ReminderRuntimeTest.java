package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
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
    private Bitmap desktopBaseline;

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
        if (desktopBaseline != null) desktopBaseline.recycle();
    }

    @Test public void homeScreenAlarmStartsServiceAndDrawsNonzeroManualBanner() throws Exception {
        schedule(11001, 8000, false);
        assertFalse("Future events must not keep a service alive", AlarmService.isRunning());
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(500);
        assertHomeResumed();
        desktopBaseline = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(desktopBaseline);
        saveBitmap("runtime-banner-baseline.png", desktopBaseline);
        awaitStage(11001, "overlay_visible", 16000);
        awaitCompositedBanner(11001);
        assertHomeResumed();
        assertServicePrecedesBackgroundOverlay(11001);
        assertTrue(hasStage(11001, "alarm_entry"));
        assertTrue(hasStage(11001, "service"));
        assertTrue(hasStage(11001, "vibration_requested"));
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals("Keep the system heads-up fallback when the app is in background", NotificationManager.IMPORTANCE_HIGH,
                manager.getNotificationChannel(AlarmDelivery.notificationChannelFor(ctx,
                        "ddlr_high_v4_vib_silent")).getImportance());
        screenshot("runtime-manual.png");
        Thread.sleep(6000);
        assertFalse("A retained banner must not retain a CPU wake lock", AlarmService.isWakeHeld());
        assertEquals("Manual banner must remain after five seconds", 1, visibleCards());
        BannerSnapshot banner = bannerSnapshot(11001);
        assertNotNull(banner);
        shell("input tap " + banner.close.centerX() + " " + banner.close.centerY());
        long dismissed = System.currentTimeMillis() + 1500;
        while (visibleCards() != 0 && System.currentTimeMillis() < dismissed) Thread.sleep(50);
        assertEquals(0, visibleCards());
        assertTrue(stageDetail(11001, "overlay_dismissed").contains("reason=user"));
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
        // A system heads-up may cover this five-second card. The manual case
        // above proves composition after that surface leaves; this case tests
        // layout, lifetime and resource release without treating bounds as pixels.
        assertNotNull(bannerSnapshot(11002));
        assertServicePrecedesBackgroundOverlay(11002);
        screenshot("runtime-auto-system-notification.png");
        assertEquals(1, visibleCards());
        Thread.sleep(6000);
        instrumentation.waitForIdleSync();
        assertEquals(0, visibleCards());
        assertTrue(stageDetail(11002, "overlay_dismissed").contains("reason=timeout"));
        assertFalse("Expired banners must stop their foreground service", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            assertNotEquals(AlarmDelivery.notificationId(11002), notification.getId());
        }
    }

    @Test public void realSavedGradientUsesBackgroundOverlayAndDeadlineKeepsNotificationFallback() throws Exception {
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        assertTrue(ReminderBanner.allowed(ctx));
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
        awaitStage(tier.getInt("id"), "overlay_visible", 4000);
        assertServicePrecedesBackgroundOverlay(tier.getInt("id"));
        assertQuietNotification(tier.getInt("id"));
        screenshot("runtime-tier-system-notification.png");
        awaitNewSystemVibration(beforeTier, true, "ALARM");
        assertFalse("Silent mode must never start the chime", hasStage(tier.getInt("id"), "sound_started"));
        assertTrue(stageDetail(tier.getInt("id"), "sound_suppressed").contains("reason=ringer_silent"));
        assertTrue(hasStage(tier.getInt("id"), "direct_vibration_requested"));
        Thread.sleep(6000);
        assertEquals("Automatic gradient card must finish after five seconds", 0, visibleCards());
        assertTrue("The actual five-minute deadline must stay protected after its gradient", AlarmService.isRunning());
        assertTrue(AlarmService.protectionState().getBoolean("waiting"));
        assertEquals(due.getLong("at"), AlarmService.protectionState().getLong("expectedAt"));
        assertFalse(AlarmService.isWakeHeld());
        // Withhold the real deadline's system callback for the entire five-
        // minute production interval. Protection must deliver it at HOME.
        ((android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(
                AlarmScheduler.operation(ctx, due.getLong("id"), due.getLong("at"), due.optString("title"),
                        due.optString("body"), due.optString("channel"), true, 0));
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        assertFalse(ReminderBanner.allowed(ctx));
        String beforeDeadline = shellText("dumpsys vibrator_manager");
        awaitStage(due.getInt("id"), "direct_vibration_requested", 315000);
        awaitStage(due.getInt("id"), "sound_suppressed", 4000);
        assertEquals("protected_timer", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastFireVia", ""));
        assertQuietNotification(due.getInt("id"));
        assertTrue("Deadline must record the denied overlay and keep its notification", hasStage(due.getInt("id"), "overlay_blocked"));
        assertEquals("Notification/vibration fallback does not need an overlay", 0, visibleCards());
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

    @Test public void realVibrationOnlyTaskReleasesServiceAfterNotification() throws Exception {
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
        assertTrue("The alarm must enter through the direct service", hasStage(id, "alarm_entry"));
        awaitStage(id, "wake_released", 3000);
        long idle = System.currentTimeMillis() + 3000;
        while (AlarmService.isRunning() && System.currentTimeMillis() < idle) Thread.sleep(25);
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
        saveBitmap(name, screen);
        screen.recycle();
    }

    private void saveBitmap(String name, Bitmap screen) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void assertServicePrecedesBackgroundOverlay(int id) throws Exception {
        int service = -1, shown = -1;
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") != id) continue;
            if ("service".equals(event.optString("stage"))) service = i;
            if ("overlay_visible".equals(event.optString("stage"))) {
                shown = i;
                assertTrue(event.optString("detail").contains("appVisible=false"));
                assertTrue(event.optString("detail").contains("attached=true"));
            }
        }
        assertTrue("The dispatch service must be foreground before attaching its background overlay", service >= 0 && shown > service);
    }

    private void assertHomeResumed() throws Exception {
        String resolved = shellText("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim();
        String component = resolved.substring(resolved.lastIndexOf('\n') + 1).trim();
        assertTrue("No HOME activity was resolved: " + resolved, component.contains("/"));
        String homePackage = component.substring(0, component.indexOf('/'));
        boolean resumed = false;
        for (String line : shellText("dumpsys activity activities").split("\\n")) {
            if ((line.contains("mResumedActivity") || line.contains("topResumedActivity")) && line.contains(homePackage + "/")) resumed = true;
        }
        assertTrue("A real launcher activity must remain in foreground", resumed);
        assertFalse(MainActivity.visible);
    }

    private static final class BannerSnapshot {
        Rect card, heading, body, close;
        String headingText, bodyText;
        int headingLines, bodyLines;
    }

    private BannerSnapshot bannerSnapshot(int id) {
        AtomicReference<BannerSnapshot> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                View card = root.findViewWithTag("ddlr-reminder-" + AlarmDelivery.notificationId(id));
                if (card == null || !card.isAttachedToWindow()) continue;
                TextView heading = findText(card, "原生后台事件"), body = findText(card, "此横幅应显示在桌面，不能是零高度");
                View close = findClose(card);
                if (heading == null || body == null || close == null) continue;
                BannerSnapshot snapshot = new BannerSnapshot();
                snapshot.card = screenBounds(card);
                snapshot.heading = screenBounds(heading);
                snapshot.body = screenBounds(body);
                snapshot.close = screenBounds(close);
                snapshot.headingText = heading.getText().toString();
                snapshot.bodyText = body.getText().toString();
                snapshot.headingLines = heading.getLineCount();
                snapshot.bodyLines = body.getLineCount();
                result.set(snapshot);
                return;
            }
        });
        return result.get();
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = findText(group.getChildAt(i), expected);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findClose(View view) {
        if ("关闭提醒".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findClose(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private static Rect screenBounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private void awaitCompositedBanner(int id) throws Exception {
        // The real screen must contain both the translucent surface and changed
        // native text pixels over a baseline screenshot of the launcher. Layout
        // rectangles alone cannot prove that SystemUI isn't covering the card.
        long until = System.currentTimeMillis() + 8000;
        int[][] encoded = blendTable(false), linear = blendTable(true);
        int surfacePixels = 0, titlePixels = 0, bodyPixels = 0, closePixels = 0;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(200);
            BannerSnapshot snapshot = bannerSnapshot(id);
            if (snapshot == null) continue;
            float density = ctx.getResources().getDisplayMetrics().density;
            assertTrue("Compact banner must stay within 360 dp", snapshot.card.width() <= Math.round(360 * density) + 1);
            assertTrue("Two-line banner must stay compact", snapshot.card.height() <= Math.round(86 * density));
            assertTrue("Close control needs at least a 44 dp touch target", snapshot.close.width() >= Math.round(44 * density)
                    && snapshot.close.height() >= Math.round(44 * density));
            assertEquals(1, snapshot.headingLines);
            assertEquals(1, snapshot.bodyLines);
            Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
            if (screen != null) {
                surfacePixels = 0;
                Rect card = new Rect(snapshot.card);
                card.intersect(0, 0, screen.getWidth(), screen.getHeight());
                for (int y = card.top + 2; y < card.bottom - 2; y++) {
                    for (int x = card.left + 2; x < card.right - 2; x++) {
                        int color = screen.getPixel(x, y), below = desktopBaseline.getPixel(x, y);
                        if (matchesBlend(color, below, encoded) || matchesBlend(color, below, linear)) surfacePixels++;
                    }
                }
                titlePixels = changedTextPixels(screen, snapshot.heading);
                bodyPixels = changedTextPixels(screen, snapshot.body);
                closePixels = changedTextPixels(screen, snapshot.close);
                screen.recycle();
                if (surfacePixels > Math.max(800, card.width() * card.height() / 4)
                        && titlePixels > 15 && bodyPixels > 15 && closePixels > 3) {
                    JSONObject evidence = new JSONObject().put("appVisible", MainActivity.visible)
                            .put("surfacePixels", surfacePixels).put("titlePixels", titlePixels).put("bodyPixels", bodyPixels)
                            .put("closePixels", closePixels).put("cardBounds", snapshot.card.toShortString())
                            .put("closeBounds", snapshot.close.toShortString()).put("title", snapshot.headingText).put("body", snapshot.bodyText);
                    try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "runtime-banner-composition.json"))) {
                        out.write(evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return;
                }
            }
        }
        screenshot("missing-banner.png");
        fail("Native card absent from the real desktop composition: surface=" + surfacePixels
                + ";title=" + titlePixels + ";body=" + bodyPixels + ";close=" + closePixels);
    }

    private int changedTextPixels(Bitmap screen, Rect bounds) {
        int count = 0;
        Rect area = new Rect(bounds);
        area.intersect(0, 0, screen.getWidth(), screen.getHeight());
        for (int y = area.top; y < area.bottom; y++) for (int x = area.left; x < area.right; x++) {
            int color = screen.getPixel(x, y), before = desktopBaseline.getPixel(x, y);
            int difference = Math.abs(Color.red(color) - Color.red(before)) + Math.abs(Color.green(color) - Color.green(before))
                    + Math.abs(Color.blue(color) - Color.blue(before));
            if (Color.red(color) > 150 && Color.green(color) > 155 && Color.blue(color) > 165 && difference > 80) count++;
        }
        return count;
    }

    private static int[][] blendTable(boolean linear) {
        int[][] table = new int[3][256];
        int surface = ReminderBanner.SURFACE_COLOR;
        int[] channels = {Color.red(surface), Color.green(surface), Color.blue(surface)};
        double alpha = Color.alpha(surface) / 255.0;
        for (int c = 0; c < 3; c++) for (int below = 0; below < 256; below++) {
            double foreground = channels[c] / 255.0, background = below / 255.0;
            if (linear) {
                foreground = foreground <= .04045 ? foreground / 12.92 : Math.pow((foreground + .055) / 1.055, 2.4);
                background = background <= .04045 ? background / 12.92 : Math.pow((background + .055) / 1.055, 2.4);
            }
            double mixed = alpha * foreground + (1 - alpha) * background;
            if (linear) mixed = mixed <= .0031308 ? 12.92 * mixed : 1.055 * Math.pow(mixed, 1 / 2.4) - .055;
            table[c][below] = (int) Math.round(mixed * 255);
        }
        return table;
    }

    private static boolean matchesBlend(int actual, int below, int[][] expected) {
        return Math.abs(Color.red(actual) - expected[0][Color.red(below)]) <= 7
                && Math.abs(Color.green(actual) - expected[1][Color.green(below)]) <= 7
                && Math.abs(Color.blue(actual) - expected[2][Color.blue(below)]) <= 7;
    }
}
