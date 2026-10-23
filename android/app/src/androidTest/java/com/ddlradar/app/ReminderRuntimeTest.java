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
        shell("cmd notification set_dnd off");
        shell("media volume --stream 5 --set 5");
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
                + "S.settings.tiered=true;S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;"
                + "S.settings.remindSilent=false;S.settings.bannerDuration='5s';"
                + "save().then(ok=>window.__saved=ok?'ready':'failed')");
        while (!"\"ready\"".equals(js(activity, "window.__saved"))) {
            assertTrue("Real event save was not acknowledged", System.currentTimeMillis() < ready);
            Thread.sleep(150);
        }
        JSONArray pending = AlarmScheduler.load(ctx);
        assertEquals("Exactly the enabled tier and the deadline", 2, pending.length());
        JSONObject tier = pending.getJSONObject(0), due = pending.getJSONObject(1);
        assertEquals(300000L, due.getLong("at") - tier.getLong("at"));
        shell("input keyevent KEYCODE_HOME");
        awaitStage(tier.getInt("id"), "sound_started", 25000);
        awaitStage(tier.getInt("id"), "sound_completed", 6000);
        assertSystemVibrationChannel(tier.getInt("id"));
        assertEquals("No overlay permission is required for system delivery", 0, visibleCards());
        assertFalse(hasStage(tier.getInt("id"), "direct_vibration_requested"));
        Thread.sleep(6000);
        assertFalse("Idle between tiers must not retain service", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        awaitStage(due.getInt("id"), "sound_started", 315000);
        awaitStage(due.getInt("id"), "sound_completed", 6000);
        assertSystemVibrationChannel(due.getInt("id"));
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
        long until = System.currentTimeMillis() + 3500;
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
