package com.ddlradar.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Ends BEFORE the host starts the cached/freezer test. No instrumentation keeps it alive. */
@RunWith(AndroidJUnit4.class)
public class FrozenReminderFixture {
    private Instrumentation instrument;
    private String js(MainActivity activity, String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> output = new AtomicReference<>();
        instrument.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(script,
                result -> { output.set(result); latch.countDown(); }));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        return output.get();
    }

    @Test public void saveRealTaskForHostFreezerTest() throws Exception {
        instrument = InstrumentationRegistry.getInstrumentation();
        Context ctx = instrument.getTargetContext();
        MainActivity activity = (MainActivity) instrument.startActivitySync(
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end = System.currentTimeMillis() + 30000;
        while (!"true".equals(js(activity, "!!(window.S && window.NativeNotify && window.planReminders)"))) {
            assertTrue(System.currentTimeMillis() < end);
            Thread.sleep(100);
        }
        js(activity, "window.__saved='pending'; S.tasks=[{id:'frozen-real-task',name:'冻结后的正常截止提醒',"
                + "dueAt:Date.now()+90000,tiers:{120:false,60:false,30:false,5:false},custom:[],"
                + "daily:false,done:false,quadrant:'q1'}];"
                + "S.settings.sound=true;S.settings.vibrate=true;S.settings.banner=true;S.settings.vibrationMode='native';"
                + "S.settings.vibrateInSilent=true;S.settings.remindSilent=false;S.settings.bannerDuration='5s';"
                + "save().then(ok=>window.__saved=ok?'ready':'failed')");
        while (!"\"ready\"".equals(js(activity, "window.__saved"))) {
            assertTrue(System.currentTimeMillis() < end);
            Thread.sleep(100);
        }
        JSONArray items = AlarmScheduler.load(ctx);
        assertEquals(1, items.length());
        assertTrue("Near task save must acknowledge finite preparation", AlarmService.isRunning());
        assertFalse("Preparation must not hold the CPU awake", AlarmService.isWakeHeld());
        assertEquals("true", js(activity, "JSON.parse(localStorage.getItem('ddlr_v1')).tasks.some(t=>t.id==='frozen-real-task')"));
        // AndroidJUnitRunner kills the target at finish. Let Chromium commit
        // localStorage before that artificial kill; HOME alone does not kill it.
        Thread.sleep(7000);
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "frozen-task.json"))) {
            out.write(items.getJSONObject(0).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
