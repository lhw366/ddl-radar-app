package com.ddlradar.app;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Framework-level regression for the phone's silent-ringer diagnostic, never a mocked motor. */
@RunWith(AndroidJUnit4.class)
public class SilentReminderRuntimeTest {
    private Context ctx;
    private Instrumentation instrumentation;
    private RuntimeDevicePolicy policy;
    private MainActivity webActivity;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        ctx = instrumentation.getTargetContext();
        policy = new RuntimeDevicePolicy(ctx, instrumentation);
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        assertFalse(ReminderBanner.allowed(ctx));
        ctx.stopService(new Intent(ctx, AlarmService.class));
        instrumentation.waitForIdleSync();
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
    }

    @After public void cleanup() throws Exception {
        try {
            JSONArray pending = AlarmScheduler.load(ctx);
            for (int i = 0; i < pending.length(); i++) AlarmScheduler.cancel(ctx, pending.getJSONObject(i).getLong("id"));
            AlarmScheduler.cancel(ctx, 990000001);
            AlarmScheduler.save(ctx, new JSONArray());
            ctx.stopService(new Intent(ctx, AlarmService.class));
            instrumentation.waitForIdleSync();
        } finally {
            try { finishWebApp(); }
            finally { if (policy != null) policy.close(); }
        }
    }

    @Test public void fiveSecondBridgeTestUsesSilentAlarmHapticsWithoutAnyChime() throws Exception {
        policy.silentWithAllInterruptions();
        MainActivity activity = openWebApp();
        configure(activity, "S.tasks=[];S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;"
                + "S.settings.vibrationMode='notification';S.settings.vibrateInSilent=true;S.settings.remindSilent=false;");
        js(activity, "window.__trial='pending';NativeNotify.test(5).then(ok=>window.__trial=ok?'ready':'failed')");
        awaitJs(activity, "window.__trial", "\"ready\"");
        JSONObject item = AlarmScheduler.load(ctx).getJSONObject(0);
        assertEquals(990000001, item.getInt("id"));
        assertTrue("Silent preference must reach persisted native data", item.getBoolean("vibrateInSilent"));
        String before = shell("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(990000001, "direct_vibration_requested", 14000);
        awaitStage(990000001, "sound_suppressed", 3000);
        assertTrue(detail(990000001, "delivery").contains("appVisible=false"));
        assertTrue(detail(990000001, "direct_vibration_requested").contains("usage=alarm;"));
        assertTrue(detail(990000001, "sound_suppressed").contains("reason=ringer_silent"));
        assertFalse(has(990000001, "sound_started"));
        assertQuietNotification(990000001);
        completedMotor(before, "ALARM");
        awaitStopped(990000001);
        assertSilentStateUnchanged();
        evidence("runtime-silent-test.json", new JSONObject().put("case", "actual NativeNotify.test(5)")
                .put("alarm", item).put("trace", ReminderTrace.read(ctx)).put("vibrations", shell("dumpsys vibrator_manager")));
    }

    @Test public void foregroundFeedbackUsesNativeSilentPolicyInsteadOfBrowserAudioOrVibration() throws Exception {
        policy.silentWithAllInterruptions();
        MainActivity activity = openWebApp();
        configure(activity, "S.tasks=[];S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;"
                + "S.settings.vibrationMode='notification';S.settings.vibrateInSilent=true;S.settings.remindSilent=false;");
        js(activity, "window.__feedbackBrowserCalls={audio:0,vibration:0};"
                + "window.__feedbackOriginals={audio:window.AudioContext,webkit:window.webkitAudioContext,"
                + "vibrate:navigator.vibrate,context:audioCtx};audioCtx=null;"
                + "window.__feedbackAudioProbe=function(){window.__feedbackBrowserCalls.audio++;throw new Error('APK used browser audio');};"
                + "window.__feedbackVibrationProbe=function(){window.__feedbackBrowserCalls.vibration++;return true;};"
                + "window.AudioContext=window.__feedbackAudioProbe;window.webkitAudioContext=window.__feedbackAudioProbe;"
                + "navigator.vibrate=window.__feedbackVibrationProbe;");
        try {
            assertEquals("true", js(activity, "window.AudioContext===window.__feedbackAudioProbe"
                    + "&&navigator.vibrate===window.__feedbackVibrationProbe"));
            String before = shell("dumpsys vibrator_manager");
            js(activity, "beep();vibe();");
            awaitStage(990000003, "direct_vibration_requested", 6000);
            awaitStage(990000003, "sound_suppressed", 3000);
            assertTrue(detail(990000003, "direct_vibration_requested").contains("usage=alarm;"));
            assertTrue(detail(990000003, "direct_vibration_requested").contains("foregroundService=false;appVisible=true"));
            assertTrue(detail(990000003, "sound_suppressed").contains("reason=ringer_silent"));
            assertFalse(has(990000003, "sound_started"));
            completedMotor(before, "ALARM");
            assertFalse("Visible foreground feedback must not start a service", AlarmService.isRunning());
            assertFalse(AlarmService.isWakeHeld());
            JSONArray enabledTrace = ReminderTrace.read(ctx);

            configure(activity, "S.settings.vibrateInSilent=false;");
            ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
            before = shell("dumpsys vibrator_manager");
            js(activity, "beep();vibe();");
            awaitStage(990000003, "vibration_blocked", 6000);
            awaitStage(990000003, "sound_suppressed", 3000);
            assertTrue(detail(990000003, "vibration_blocked").contains("reason=silent_vibration_disabled"));
            assertFalse(has(990000003, "direct_vibration_requested"));
            assertFalse(has(990000003, "sound_started"));
            assertNoNewMotor(before);
            assertEquals("0", js(activity, "window.__feedbackBrowserCalls.audio"));
            assertEquals("0", js(activity, "window.__feedbackBrowserCalls.vibration"));
            assertSilentStateUnchanged();
            evidence("runtime-foreground-feedback.json", new JSONObject().put("webAudioCalls", 0)
                    .put("navigatorVibrationCalls", 0).put("enabledTrace", enabledTrace)
                    .put("disabledTrace", ReminderTrace.read(ctx)).put("vibrations", shell("dumpsys vibrator_manager")));
        } finally {
            js(activity, "if(window.__feedbackOriginals){window.AudioContext=window.__feedbackOriginals.audio;"
                    + "window.webkitAudioContext=window.__feedbackOriginals.webkit;"
                    + "navigator.vibrate=window.__feedbackOriginals.vibrate;audioCtx=window.__feedbackOriginals.context;"
                    + "delete window.__feedbackOriginals;}");
        }
    }

    @Test public void silentPreferenceVibrationToggleAndGlobalSilencePreventTheMotor() throws Exception {
        policy.silentWithAllInterruptions();
        MainActivity activity = openWebApp();
        configure(activity, "S.tasks=[];S.settings.sound=false;S.settings.vibrate=true;S.settings.banner=true;"
                + "S.settings.vibrationMode='native';S.settings.vibrateInSilent=false;S.settings.remindSilent=false;");
        int disabledByPreference = saveTask(activity, "silent-opt-out");
        String before = shell("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(disabledByPreference, "notification", 12000);
        awaitStage(disabledByPreference, "vibration_blocked", 1500);
        assertTrue(detail(disabledByPreference, "delivery").contains("appVisible=false"));
        assertTrue(detail(disabledByPreference, "vibration_blocked").contains("status=blocked_preference"));
        assertFalse(has(disabledByPreference, "direct_vibration_requested"));
        assertFalse(has(disabledByPreference, "service"));
        assertQuietNotification(disabledByPreference);
        assertNoNewMotor(before);

        activity = openWebApp();
        configure(activity, "S.tasks=[];S.settings.sound=false;S.settings.vibrate=false;S.settings.banner=true;"
                + "S.settings.vibrationMode='native';S.settings.vibrateInSilent=true;S.settings.remindSilent=false;");
        int disabledVibration = saveTask(activity, "vibration-disabled");
        before = shell("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(disabledVibration, "notification", 12000);
        awaitStage(disabledVibration, "vibration_disabled", 1500);
        assertTrue(detail(disabledVibration, "vibration_disabled").contains("status=disabled"));
        assertFalse(has(disabledVibration, "direct_vibration_requested"));
        assertFalse(has(disabledVibration, "service"));
        assertNoNewMotor(before);

        activity = openWebApp();
        configure(activity, "S.tasks=[{id:'globally-silent',name:'全部静默',dueAt:Date.now()+120000,"
                + "tiers:{120:false,60:false,30:false,5:false},custom:[],daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;"
                + "S.settings.vibrateInSilent=true;S.settings.remindSilent=true;");
        assertEquals("Global app silence must remove future native alarms", 0, AlarmScheduler.load(ctx).length());
        js(activity, "window.__trial='pending';NativeNotify.test(5).then(()=>window.__trial='ready')");
        awaitJs(activity, "window.__trial", "\"ready\"");
        assertEquals("A globally silent test must not schedule a hidden motor request", 0, AlarmScheduler.load(ctx).length());
        assertSilentStateUnchanged();
        evidence("runtime-silent-disabled.json", new JSONObject().put("trace", ReminderTrace.read(ctx))
                .put("remainingAlarms", AlarmScheduler.load(ctx)).put("vibrations", shell("dumpsys vibrator_manager")));
    }

    @Test public void dndNeverPromotesHapticsAndNormalModeKeepsItsFiniteChimeAndVolume() throws Exception {
        policy.silentWithAllInterruptions();
        JSONArray results = new JSONArray();
        for (int filter : new int[]{NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_PRIORITY}) {
            policy.interruptionFilter(filter);
            int id = 11100 + filter;
            JSObject result = AlarmDelivery.requestNativeVibration(ctx, id, true);
            assertNotEquals("A DND filter must never be promoted to alarm usage", "alarm", result.optString("usage"));
            assertFalse(detail(id, "direct_vibration_requested").contains("usage=alarm;"));
            if (filter != NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    || policy.audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                assertFalse(result.optBoolean("requested"));
                assertEquals("do_not_disturb", result.optString("suppressed"));
            }
            results.put(new JSONObject().put("filter", filter).put("ringer", policy.audio.getRingerMode()).put("result", result));
        }
        evidence("runtime-silent-dnd.json", new JSONObject().put("cases", results).put("trace", ReminderTrace.read(ctx)));

        policy.normalWithAudibleNotificationVolume();
        int originalVolume = policy.audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        MainActivity activity = openWebApp();
        configure(activity, "S.tasks=[];S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=false;"
                + "S.settings.vibrationMode='native';S.settings.vibrateInSilent=true;S.settings.remindSilent=false;");
        int id = saveTask(activity, "normal-finite-chime");
        String before = shell("dumpsys vibrator_manager");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(id, "sound_started", 12000);
        awaitStage(id, "sound_completed", 5000);
        assertTrue(detail(id, "delivery").contains("appVisible=false"));
        assertTrue(detail(id, "direct_vibration_requested").contains("usage=notification;"));
        completedMotor(before, "NOTIFICATION");
        awaitStopped(id);
        assertEquals(AudioManager.RINGER_MODE_NORMAL, policy.audio.getRingerMode());
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, policy.notifications.getCurrentInterruptionFilter());
        assertEquals("The reminder must preserve the user's volume", originalVolume,
                policy.audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
        evidence("runtime-normal-sound.json", new JSONObject().put("volumeBefore", originalVolume)
                .put("volumeAfter", policy.audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                .put("trace", ReminderTrace.read(ctx)).put("vibrations", shell("dumpsys vibrator_manager")));
    }

    private MainActivity openWebApp() throws Exception {
        // MainActivity is singleTask: returning from HOME resumes its existing
        // instance through onNewIntent. startActivitySync waits for a newly
        // created Activity and therefore times out on the second return.
        instrumentation.runOnMainSync(() -> ctx.startActivity(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            AtomicReference<MainActivity> resumed = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MainActivity && !activity.isFinishing() && !activity.isDestroyed())
                        resumed.set((MainActivity) activity);
                }
            });
            if (resumed.get() != null && MainActivity.visible) {
                webActivity = resumed.get();
                awaitJs(webActivity, "!!(window.S && window.NativeNotify && window.planReminders)", "true");
                return webActivity;
            }
            Thread.sleep(75);
        }
        fail("MainActivity did not reach RESUMED after launch or return from HOME");
        return null;
    }

    private void finishWebApp() throws Exception {
        MainActivity activity = webActivity;
        if (activity == null) return;
        instrumentation.runOnMainSync(() -> { if (!activity.isDestroyed()) activity.finish(); });
        long until = System.currentTimeMillis() + 5000;
        while (!activity.isDestroyed() && System.currentTimeMillis() < until) Thread.sleep(50);
        assertTrue("Each test must dispose its web Activity", activity.isDestroyed());
        webActivity = null;
    }

    private void configure(MainActivity activity, String settings) throws Exception {
        js(activity, "window.__saved='pending';" + settings + "S.settings.bannerDuration='5s';"
                + "save().then(ok=>window.__saved=ok?'ready':'failed')");
        awaitJs(activity, "window.__saved", "\"ready\"");
    }

    private int saveTask(MainActivity activity, String name) throws Exception {
        configure(activity, "S.tasks=[{id:'" + name + "',name:'真实正常任务',dueAt:Date.now()+4000,"
                + "tiers:{120:false,60:false,30:false,5:false},custom:[],daily:false,done:false,quadrant:'q1'}];");
        JSONArray pending = AlarmScheduler.load(ctx);
        assertEquals(1, pending.length());
        return pending.getJSONObject(0).getInt("id");
    }

    private void assertQuietNotification(int id) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification entry : manager.getActiveNotifications()) {
            if (entry.getId() != AlarmDelivery.notificationId(id)) continue;
            android.app.NotificationChannel channel = manager.getNotificationChannel(entry.getNotification().getChannelId());
            assertEquals("ddlr_high_v6_quiet", channel.getId());
            assertFalse(channel.shouldVibrate());
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.getImportance());
            return;
        }
        fail("The actual silent system notification is missing");
    }

    private void assertSilentStateUnchanged() {
        assertEquals(AudioManager.RINGER_MODE_SILENT, policy.audio.getRingerMode());
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, policy.notifications.getCurrentInterruptionFilter());
        assertEquals(0, policy.audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
    }

    private void completedMotor(String before, String usage) throws Exception {
        long until = System.currentTimeMillis() + 4000;
        String after = "";
        while (System.currentTimeMillis() < until) {
            after = shell("dumpsys vibrator_manager");
            for (String line : after.split("\\n")) {
                if (line.contains("| " + ctx.getPackageName() + " (uid=") && line.contains("usage: " + usage + " |")
                        && line.contains("finished") && !before.contains(line.trim())) return;
            }
            Thread.sleep(100);
        }
        fail("No completed app-owned " + usage + " waveform: " + after);
    }

    private void assertNoNewMotor(String before) throws Exception {
        Thread.sleep(200);
        for (String line : shell("dumpsys vibrator_manager").split("\\n")) {
            if (line.contains(ctx.getPackageName()) && line.contains("|   effect |") && !before.contains(line.trim()))
                fail("A disabled reminder produced a new system vibration: " + line);
        }
    }

    private void awaitStopped(int id) throws Exception {
        awaitStage(id, "wake_released", 5000);
        long until = System.currentTimeMillis() + 1500;
        while (AlarmService.isRunning() && System.currentTimeMillis() < until) Thread.sleep(50);
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    private void awaitStage(int id, String stage, long timeout) throws Exception {
        long until = System.currentTimeMillis() + timeout;
        while (!has(id, stage) && System.currentTimeMillis() < until) Thread.sleep(75);
        assertTrue("Missing " + stage + ": " + ReminderTrace.read(ctx), has(id, stage));
    }

    private boolean has(int id, String stage) throws Exception { return !detail(id, stage).isEmpty(); }

    private String detail(int id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = 0; i < trace.length(); i++) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") == id && stage.equals(event.optString("stage"))) return event.optString("detail");
        }
        return "";
    }

    private void awaitJs(MainActivity activity, String expression, String expected) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        String actual;
        do {
            actual = js(activity, expression);
            if (expected.equals(actual)) return;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < until);
        fail("JavaScript did not reach " + expected + ": " + expression + " returned " + actual);
    }

    private String js(MainActivity activity, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> output = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(script,
                value -> { output.set(value); done.countDown(); }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return output.get();
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }

    private void evidence(String name, JSONObject value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
