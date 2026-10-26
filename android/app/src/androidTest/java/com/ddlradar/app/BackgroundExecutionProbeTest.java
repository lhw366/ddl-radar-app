package com.ddlradar.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundExecutionProbeTest {
    private final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void independentWorkerObservesABlockedMainQueueAndThenTerminates() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            blocked.countDown();
            android.os.SystemClock.sleep(2300);
        });
        assertTrue(blocked.await(1, TimeUnit.SECONDS));
        long at = System.currentTimeMillis() + 400;
        BackgroundExecutionProbe.arm(ctx, at);
        long until = System.currentTimeMillis() + 1800;
        JSONObject state;
        do { Thread.sleep(25); state = BackgroundExecutionProbe.read(ctx); }
        while (!state.has("workerAt") && System.currentTimeMillis() < until);
        assertTrue(state.has("workerAt"));
        assertTrue(state.getLong("workerAt") - at < 1000);
        assertFalse("Main queue must still be blocked", state.has("mainAt"));
        assertTrue(state.getJSONArray("mainStack").length() > 0);
        until = System.currentTimeMillis() + 3500;
        while (!BackgroundExecutionProbe.read(ctx).optBoolean("finished") && System.currentTimeMillis() < until) Thread.sleep(25);
        state = BackgroundExecutionProbe.read(ctx);
        assertTrue(state.getBoolean("finished"));
        assertTrue(state.getLong("mainQueueDelayMs") >= 1500);
        until = System.currentTimeMillis() + 1000;
        while (BackgroundExecutionProbe.isWorkerRunning() && System.currentTimeMillis() < until) Thread.sleep(25);
        assertFalse(BackgroundExecutionProbe.isWorkerRunning());
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }

    @Test public void rearmingReplacesTheOldObservationWithoutDeliveringAnAlarm() throws Exception {
        BackgroundExecutionProbe.arm(ctx, System.currentTimeMillis() + 500);
        long at = System.currentTimeMillis() + 1100;
        BackgroundExecutionProbe.arm(ctx, at);
        long until = System.currentTimeMillis() + 3500;
        while (!BackgroundExecutionProbe.read(ctx).optBoolean("finished") && System.currentTimeMillis() < until) Thread.sleep(25);
        JSONObject state = BackgroundExecutionProbe.read(ctx);
        assertEquals(at, state.getLong("expectedAt"));
        assertTrue(state.getLong("workerAt") >= at);
        assertTrue(state.getBoolean("finished"));
        assertEquals(0, AlarmScheduler.load(ctx).length());
        assertFalse(AlarmService.isRunning());
        assertFalse(AlarmService.isWakeHeld());
    }
}
