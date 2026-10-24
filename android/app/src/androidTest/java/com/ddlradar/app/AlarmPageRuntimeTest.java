package com.ddlradar.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real activity/window lifetime checks; screen controls are limited to the disposable emulator. */
@RunWith(AndroidJUnit4.class)
public class AlarmPageRuntimeTest {
    private static final JSONArray cases = new JSONArray();
    private Instrumentation instrumentation;
    private Context ctx;
    private AlarmActivity activity;
    private boolean disposableEmulator;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        ctx = instrumentation.getTargetContext();
        assertEquals("Never change the screen state of a physical phone", "1", shell("getprop ro.kernel.qemu").trim());
        disposableEmulator = true;
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
    }

    @After public void cleanup() throws Exception {
        try {
            if (activity != null) instrumentation.runOnMainSync(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) activity.finish();
            });
            instrumentation.waitForIdleSync();
        } finally {
            if (disposableEmulator) {
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
            }
        }
    }

    @Test public void automaticSmallCardClosesAfterFiveSecondsWithoutHoldingScreenAwake() throws Exception {
        open(14001, true);
        assertCompactCard();
        awaitClosed(14001, "timeout", 8000);
        long shown = event(14001, "alarm_page_shown").getLong("at");
        long closed = event(14001, "alarm_page_closed").getLong("at");
        assertTrue("Automatic card must remain briefly visible", closed - shown >= 3500);
        assertTrue("Automatic card has a finite five-second lifetime", closed - shown < 8000);
        record("automatic", new JSONObject().put("visibleMs", closed - shown));
    }

    @Test public void manualSmallCardSurvivesFiveSecondsAndClosesThroughItsRealButton() throws Exception {
        open(14002, false);
        assertCompactCard();
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "alarm-page-compact.png"))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { screen.recycle(); }
        Thread.sleep(5600);
        assertFalse("Manual mode must wait for the user", activity.isFinishing());
        assertFalse(activity.isDestroyed());
        AtomicReference<Rect> button = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> button.set(bounds(activity.getWindow().getDecorView().findViewWithTag("ddlr-alarm-ack"))));
        Rect target = button.get();
        shell("input tap " + target.centerX() + " " + target.centerY());
        awaitClosed(14002, "user", 3000);
        record("manual_real_tap", new JSONObject().put("buttonBounds", target.toShortString()));
    }

    @Test public void manualCardClosesWhenTheActualSystemScreenSleeps() throws Exception {
        open(14003, false);
        assertCompactCard();
        shell("input keyevent KEYCODE_SLEEP");
        PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        long end = System.currentTimeMillis() + 3000;
        while (power.isInteractive() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertFalse("The reminder must allow normal system sleep", power.isInteractive());
        awaitClosed(14003, "screen_off", 3000);
        assertFalse("Closing the card must not create a reminder service", AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
        record("system_screen_sleep", new JSONObject().put("screenInteractive", power.isInteractive()));
    }

    private void open(int id, boolean automatic) throws Exception {
        Intent intent = new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("notifId", AlarmDelivery.notificationId(id)).putExtra("autoDismiss", automatic)
                .putExtra("title", "紧凑锁屏提醒").putExtra("body", "到点后确认即可关闭，不持续保持屏幕常亮。");
        // AlarmActivity is singleTask: wait for RESUMED instead of requiring a newly created instance.
        instrumentation.runOnMainSync(() -> ctx.startActivity(intent));
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end) {
            instrumentation.runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof AlarmActivity && !candidate.isFinishing() && !candidate.isDestroyed())
                        activity = (AlarmActivity) candidate;
            });
            if (activity != null && event(id, "alarm_page_shown") != null) return;
            Thread.sleep(50);
        }
        fail("The real singleTask reminder activity did not resume and draw");
    }

    private void assertCompactCard() {
        instrumentation.runOnMainSync(() -> {
            View card = activity.getWindow().getDecorView().findViewWithTag("ddlr-alarm-card");
            View button = activity.getWindow().getDecorView().findViewWithTag("ddlr-alarm-ack");
            assertNotNull(card);
            assertTrue(card.isShown());
            float density = ctx.getResources().getDisplayMetrics().density;
            assertTrue("Card width must stay compact", card.getWidth() <= Math.round(360 * density));
            assertTrue("Reminder must be a small card instead of a full-screen page",
                    card.getHeight() < ctx.getResources().getDisplayMetrics().heightPixels / 2);
            assertTrue(button.getWidth() >= Math.round(44 * density));
            assertTrue(button.getHeight() >= Math.round(44 * density));
            assertEquals("No permanent screen-on flag", 0,
                    activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private void awaitClosed(int id, String reason, int waitMs) throws Exception {
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            JSONObject closed = event(id, "alarm_page_closed");
            if (closed != null && (activity.isFinishing() || activity.isDestroyed())) {
                assertEquals("reason=" + reason, closed.getString("detail"));
                return;
            }
            Thread.sleep(50);
        }
        fail("Reminder card did not close: " + reason);
    }

    private JSONObject event(int id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject value = trace.getJSONObject(i);
            if (value.optLong("id") == id && stage.equals(value.optString("stage"))) return value;
        }
        return null;
    }

    private static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }

    private void record(String name, JSONObject result) throws Exception {
        cases.put(new JSONObject().put("case", name).put("result", result).put("trace", ReminderTrace.read(ctx)));
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "alarm-page-runtime.json"))) {
            out.write(cases.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.FileInputStream in = new java.io.FileInputStream(descriptor.getFileDescriptor());
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }
}
