package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real notification submission with an injected OS service-start failure. */
@RunWith(AndroidJUnit4.class)
public class BackgroundFallbackTest {
    @Test public void grantedOverlayCannotSuppressSystemHeadsUpWhenServiceFails() throws Exception {
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context ctx = instrument.getTargetContext();
        final int id = 11007;
        shell(instrument, "appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        shell(instrument, "input keyevent KEYCODE_HOME");
        assertTrue(ReminderBanner.allowed(ctx));
        assertFalse(MainActivity.visible);
        assertFalse(AlarmService.isRunning());
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
        long at = System.currentTimeMillis() - 10;
        String channel = "ddlr_high_v4_still_silent";
        JSONObject event = new JSONObject().put("id", id).put("at", at)
                .put("title", "后台派发失败兜底").put("body", "系统横幅独立保留")
                .put("channel", channel).put("vibrate", false).put("scheduled", true)
                .put("v", AlarmScheduler.SCHED_VER);
        AlarmScheduler.save(ctx, new JSONArray().put(event));
        // This injected receiver callback represents an already registered alarm.
        ctx.getSharedPreferences("ddlr_alarms", 0).edit().putLong("registered_" + id, at).commit();
        // Context is the platform boundary. No production flag or test-only
        // path is needed to reproduce ForegroundServiceStartNotAllowed.
        Context unavailableService = new ContextWrapper(ctx) {
            @Override public ComponentName startForegroundService(Intent service) {
                throw new IllegalStateException("Injected background service start rejection");
            }
        };
        Intent alarm = new Intent(ctx, AlarmReceiver.class).putExtra("id", id).putExtra("at", at);
        try {
            instrument.runOnMainSync(() -> new AlarmReceiver().onReceive(unavailableService, alarm));
            NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean found = false;
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() != AlarmDelivery.notificationId(id)) continue;
                found = true;
                assertTrue(active.getNotification().getChannelId().startsWith("ddlr_high_"));
                assertEquals(NotificationManager.IMPORTANCE_HIGH,
                        manager.getNotificationChannel(active.getNotification().getChannelId()).getImportance());
            }
            assertTrue("A failed optional service must leave the real system notification", found);
            boolean posted = false, failed = false;
            JSONArray trace = ReminderTrace.read(ctx);
            for (int i = 0; i < trace.length(); i++) {
                JSONObject e = trace.getJSONObject(i);
                if (e.optInt("id") != id) continue;
                if ("notification".equals(e.optString("stage"))) posted = true;
                if ("effects_unavailable".equals(e.optString("stage"))) {
                    assertTrue("Notification must precede the failed service", posted);
                    failed = true;
                }
                assertNotEquals("No overlay can be declared visible without a running service",
                        "overlay_visible", e.optString("stage"));
            }
            assertTrue(failed);
            assertEquals(0, ReminderBanner.visibleCount());
            assertFalse(AlarmService.isRunning());
            assertFalse(AlarmService.isWakeHeld());
        } finally {
            AlarmScheduler.cancel(ctx, id);
            AlarmDelivery.cancelNotification(ctx, AlarmDelivery.notificationId(id));
            AlarmScheduler.save(ctx, new JSONArray());
        }
    }

    private void shell(Instrumentation instrument, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrument.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }
}
