package com.ddlradar.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.inspector.WindowInspector;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real desktop input, including both cold launch and an already running WebView. */
@RunWith(AndroidJUnit4.class)
public class ReminderBannerInteractionTest {
    private static final int FIRST = 28101, SECOND = 28102;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context ctx = instrumentation.getTargetContext();
    private MainActivity main;

    @Before public void prepare() throws Exception {
        assertEquals("This input test is limited to the disposable emulator", "1", shell("getprop ro.kernel.qemu").trim());
        shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        assertTrue("Overlay permission is a required test precondition", ReminderBanner.allowed(ctx));
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        ReminderBanner.dismissAll();
        instrumentation.waitForIdleSync();
        ctx.getSharedPreferences(ReminderTrace.PREFS, 0).edit().clear().commit();
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(300);
    }

    @After public void cleanup() {
        ReminderBanner.dismissAll();
        instrumentation.runOnMainSync(() -> {
            if (main != null && !main.isFinishing() && !main.isDestroyed()) main.finish();
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void swipeUpDismissesOnlyTheTouchedCardWithoutOpeningTheApp() throws Exception {
        showPair();
        Rect card = bounds(FIRST);
        int x = card.centerX(), start = card.bottom - dp(12), end = Math.max(1, card.top - dp(16));
        shell("input swipe " + x + " " + start + " " + x + " " + end + " 240");
        awaitCards(1);
        assertNull(findCard(FIRST));
        assertNotNull("A simultaneous reminder must remain", findCard(SECOND));
        assertTrue(detail(FIRST, "overlay_dismissed").contains("reason=swipe"));
        assertFalse(MainActivity.visible);
        assertEquals("Swiping is not a content tap", "", detail(FIRST, "reminder_open"));
        assertFalse(AlarmService.isWakeHeld());
    }

    @Test public void contentTapRestoresTheAppAndSendsItsExactReminderToTheRunningWebView() throws Exception {
        instrumentation.runOnMainSync(() -> ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        main = awaitMain();
        awaitJs("!!(window.S && window.AppBridge && window.Capacitor && Capacitor.Plugins.App)", "true");
        js("window.__bannerReady=false;window.__bannerUrl='';Promise.resolve(Capacitor.Plugins.App.addListener('appUrlOpen',"
                + "e=>{window.__bannerUrl=e.url})).then(()=>Capacitor.Plugins.App.getState()).then(()=>window.__bannerReady=true)");
        awaitJs("window.__bannerReady", "true");
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(250);
        showPair();
        tapContent(FIRST);
        awaitMain();
        awaitJs("window.__bannerUrl==='ddlr://reminder/" + FIRST + "'", "true");
        awaitCards(1);
        assertNotNull("Opening one card cannot dismiss another", findCard(SECOND));
        assertTrue(detail(FIRST, "overlay_dismissed").contains("reason=open"));
        assertEquals("source=card", detail(FIRST, "reminder_open"));
    }

    @Test public void contentTapColdLaunchesAnExplicitReminderUrlWithoutReplayingOtherCards() throws Exception {
        // No Activity is needed to draw a user-authorized application overlay.
        instrumentation.runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED))
                if (activity instanceof MainActivity && !activity.isFinishing()) activity.finish();
        });
        instrumentation.waitForIdleSync();
        showPair();
        tapContent(SECOND);
        main = awaitMain();
        awaitJs("!!(window.Capacitor && Capacitor.Plugins.App)", "true");
        js("window.__bannerColdUrl='';Capacitor.Plugins.App.getLaunchUrl().then(e=>window.__bannerColdUrl=e.url)");
        awaitJs("window.__bannerColdUrl==='ddlr://reminder/" + SECOND + "'", "true");
        awaitCards(1);
        assertNotNull(findCard(FIRST));
        assertTrue(detail(SECOND, "overlay_dismissed").contains("reason=open"));
        assertEquals(Intent.ACTION_VIEW, ReminderNavigation.intent(ctx, AlarmDelivery.notificationId(SECOND)).getAction());
        assertEquals("com.ddlradar.app.MainActivity", ReminderNavigation.intent(ctx, SECOND).getComponent().getClassName());
    }

    @Test public void sidewaysDragDoesNotOpenOrDismissAReminder() throws Exception {
        showPair();
        Rect card = bounds(FIRST);
        int y = card.bottom - dp(15);
        shell("input swipe " + (card.left + dp(40)) + " " + y + " " + (card.right - dp(40)) + " " + y + " 280");
        instrumentation.waitForIdleSync();
        assertEquals(2, cardCount());
        assertFalse(MainActivity.visible);
        assertEquals("", detail(FIRST, "reminder_open"));
        assertEquals("", detail(FIRST, "overlay_dismissed"));
    }

    @Test public void realTaskTapOpensItsEditorWithoutChangingTheTaskOrOtherCard() throws Exception {
        instrumentation.runOnMainSync(() -> ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        main = awaitMain();
        awaitJs("!!(window.S && window.AppBridge && window.NativeNotify"
                + " && document.getElementById('editor').dataset.gestureBound==='1')", "true");
        js("S.onboarded=true;document.getElementById('onboard').classList.add('hidden');"
                + "S.tasks=[{id:'banner-real-task',name:'横幅对应事项',quadrant:'q1',dueAt:Date.now()+3600000,"
                + "custom:[],tiers:{},daily:false}];window.__taskBefore=JSON.stringify(S.tasks)");
        int taskId = Integer.parseInt(js("NativeNotify.idOf('banner-real-task|due')"));
        shell("input keyevent KEYCODE_HOME");
        Thread.sleep(250);
        ReminderBanner.show(ctx, AlarmDelivery.notificationId(taskId), "对应事项到点", "点击查看", false);
        ReminderBanner.show(ctx, AlarmDelivery.notificationId(SECOND), "另一条独立提醒", "保持显示", false);
        awaitCards(2);
        long until = System.currentTimeMillis() + 3000;
        while (bounds(taskId).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50);
        tapContent(taskId);
        awaitMain();
        awaitJs("document.getElementById('fName').value==='横幅对应事项'"
                + " && !document.getElementById('editorWrap').classList.contains('hidden')", "true");
        assertEquals("true", js("JSON.stringify(S.tasks)===window.__taskBefore"));
        awaitCards(1);
        assertNotNull(findCard(SECOND));
    }

    private void showPair() throws Exception {
        ReminderBanner.show(ctx, AlarmDelivery.notificationId(FIRST), "第一条独立提醒", "上滑收起，点击查看事项", false);
        ReminderBanner.show(ctx, AlarmDelivery.notificationId(SECOND), "第二条独立提醒", "另一条提醒继续保留", false);
        awaitCards(2);
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (!bounds(FIRST).isEmpty() && !bounds(SECOND).isEmpty()) { Thread.sleep(120); return; }
            Thread.sleep(40);
        }
        fail("Both cards must have actual input bounds");
    }

    private void tapContent(int id) throws Exception {
        Rect card = bounds(id);
        shell("input tap " + card.centerX() + " " + (card.bottom - dp(18)));
    }

    private View findCard(int id) {
        AtomicReference<View> found = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            String tag = "ddlr-reminder-" + AlarmDelivery.notificationId(id);
            for (View root : WindowInspector.getGlobalWindowViews()) {
                View card = root.findViewWithTag(tag);
                if (card != null && card.isShown()) found.set(card);
            }
        });
        return found.get();
    }

    private Rect bounds(int id) {
        View card = findCard(id);
        AtomicReference<Rect> result = new AtomicReference<>(new Rect());
        if (card != null) instrumentation.runOnMainSync(() -> {
            int[] pos = new int[2]; card.getLocationOnScreen(pos);
            result.set(new Rect(pos[0], pos[1], pos[0] + card.getWidth(), pos[1] + card.getHeight()));
        });
        return result.get();
    }

    private int cardCount() {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        instrumentation.runOnMainSync(() -> count.set(ReminderBanner.visibleCount()));
        return count.get();
    }

    private void awaitCards(int count) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (cardCount() == count) return;
            Thread.sleep(40);
        }
        assertEquals("Independent cards did not reach expected count: " + ReminderTrace.read(ctx), count, cardCount());
    }

    private MainActivity awaitMain() throws Exception {
        long end = System.currentTimeMillis() + 6000;
        AtomicReference<MainActivity> found = new AtomicReference<>();
        while (System.currentTimeMillis() < end) {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof MainActivity && !activity.isFinishing()) found.set((MainActivity) activity);
            });
            if (found.get() != null && MainActivity.visible) { main = found.get(); return main; }
            Thread.sleep(40);
        }
        fail("Content tap must resume MainActivity");
        return null;
    }

    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> main.getBridge().getWebView().evaluateJavascript(script, value -> {
            result.set(value); done.countDown();
        }));
        assertTrue("WebView result timed out", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private void awaitJs(String expression, String expected) throws Exception {
        long end = System.currentTimeMillis() + 6000;
        String actual = "";
        while (System.currentTimeMillis() < end) {
            actual = js(expression);
            if (expected.equals(actual)) return;
            Thread.sleep(60);
        }
        assertEquals(expression, expected, actual);
    }

    private String detail(int id, String stage) throws Exception {
        JSONArray trace = ReminderTrace.read(ctx);
        for (int i = trace.length() - 1; i >= 0; i--) {
            JSONObject event = trace.getJSONObject(i);
            if (event.optInt("id") == id && stage.equals(event.optString("stage"))) return event.optString("detail");
        }
        return "";
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private int dp(int value) { return Math.round(value * ctx.getResources().getDisplayMetrics().density); }
}
