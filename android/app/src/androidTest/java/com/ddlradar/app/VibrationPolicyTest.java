package com.ddlradar.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Reproduces the phone's accepted notification with shouldVibrate=false. */
@RunWith(AndroidJUnit4.class)
public class VibrationPolicyTest {
    private Context ctx;
    @Before public void prepare() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
        ctx.startActivity(new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = new byte[1024]; while (input.read(bytes) != -1) { }
        }
        assertFalse(ReminderBanner.allowed(ctx));
    }
    @After public void cleanup() {
        for (int id : new int[]{11005, 11006}) {
            AlarmScheduler.cancel(ctx, id);
            AlarmDelivery.cancelNotification(ctx, AlarmDelivery.notificationId(id));
        }
        AlarmScheduler.save(ctx, new JSONArray());
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }
    @Test public void acceptedNotificationWithDisabledChannelDoesNotClaimVibration() throws Exception {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "ddlr_high_v4_vib_silent";
        assertNull("Run this fixture before the web app creates default channels", manager.getNotificationChannel(channel));
        NotificationChannel definition = new NotificationChannel(channel, "用户关闭震动的通道", 4);
        definition.enableVibration(false);
        definition.setSound(null, null);
        manager.createNotificationChannel(definition);
        deliver(11005, channel, true);
        assertEquals("blocked_channel", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastVibrationStatus", ""));
        assertFalse(has(11005, "vibration_requested"));
        assertFalse(has(11005, "direct_vibration_requested"));
        assertTrue(has(11005, "vibration_blocked"));
        assertFalse(AlarmService.isRunning());
    }
    @Test public void nativeChoiceWithVibrationOffDoesNotRequestTheMotor() throws Exception {
        deliver(11006, "ddlr_gentle_v5_native_silent", false);
        assertEquals("disabled", ctx.getSharedPreferences("ddlr_alarms", 0).getString("lastVibrationStatus", ""));
        assertFalse(has(11006, "direct_vibration_requested"));
        assertFalse(AlarmService.isRunning());
    }
    private void deliver(int id, String channel, boolean vibrate) throws Exception {
        long at = System.currentTimeMillis() + 3500;
        JSONObject item = new JSONObject().put("id", id).put("at", at).put("title", "正常事件输出策略")
                .put("body", "通知接受不等于震动发生").put("channel", channel).put("vibrate", vibrate)
                .put("scheduled", true).put("v", AlarmScheduler.SCHED_VER);
        AlarmScheduler.save(ctx, new JSONArray().put(item));
        AlarmScheduler.schedule(ctx, id, at, item.getString("title"), item.getString("body"), channel, vibrate);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
        long end = System.currentTimeMillis() + 12000;
        while (!has(id, "notification") && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue("Alarm never reached delivery", has(id, "notification"));
        Thread.sleep(250);
    }
    private boolean has(int id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") == id && stage.equals(event.optString("stage"))) return true;
        }
        return false;
    }
}
