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
        shell("input keyevent KEYCODE_HOME");
        awaitStage(11001, "overlay_visible", 16000);
        assertTrue(hasStage(11001, "receiver"));
        assertTrue(hasStage(11001, "service"));
        assertTrue(hasStage(11001, "vibration_requested"));
        screenshot("runtime-manual.png");
        Thread.sleep(6000);
        assertEquals("Manual banner must remain after five seconds", 1, visibleCards());
        ReminderBanner.dismiss(AlarmDelivery.notificationId(11001));
        instrumentation.waitForIdleSync();
        assertEquals(0, visibleCards());
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
        assertEquals(1, visibleCards());
        Thread.sleep(6000);
        instrumentation.waitForIdleSync();
        assertEquals(0, visibleCards());
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            assertNotEquals(AlarmDelivery.notificationId(11002), notification.getId());
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
}
