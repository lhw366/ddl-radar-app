package com.ddlradar.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verify the actual Release WebView layout and touch gestures, beyond the DOM fixture. */
@RunWith(AndroidJUnit4.class)
public class FormUxRuntimeTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Context context;

    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(
                script, value -> { result.set(value); done.countDown(); }));
        assertTrue("WebView script timed out", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    private void screenshot(String name) throws Exception {
        dismissUpdateNotice();
        instrumentation.waitForIdleSync();
        awaitVisual();
        Thread.sleep(400);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private void awaitVisual() throws Exception {
        CountDownLatch drawn = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().postVisualStateCallback(
                System.nanoTime(), new android.webkit.WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) { drawn.countDown(); }
                }));
        assertTrue("WebView did not commit its visual state", drawn.await(15, TimeUnit.SECONDS));
    }

    private void dismissUpdateNotice() throws Exception {
        js("localStorage.setItem('ddlr_updateChecked',String(Date.now()));"
                + "if(document.getElementById('updateLater'))document.getElementById('updateLater').click()");
    }

    private void swipe(float fromX, float fromY, float toX, float toY) throws Exception {
        float scale = Float.parseFloat(js("window.devicePixelRatio"));
        int[] offset = new int[2];
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().getLocationOnScreen(offset));
        String command = "input swipe " + Math.round(fromX * scale + offset[0]) + " "
                + Math.round(fromY * scale + offset[1]) + " " + Math.round(toX * scale + offset[0])
                + " " + Math.round(toY * scale + offset[1]) + " 300";
        try (android.os.ParcelFileDescriptor result = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.FileInputStream input = new java.io.FileInputStream(result.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) { }
        }
        Thread.sleep(350);
    }

    @Test public void themedPickerScrollableSheetAndSimpleSettingsWorkInReleaseWebView() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = System.currentTimeMillis() + 30000;
        while (!"true".equals(js("!!(window.S && window.AppGestures && document.getElementById('fDuePicker')"
                + " && document.getElementById('editor').dataset.gestureBound==='1')"))) {
            assertTrue("Application did not initialize", System.currentTimeMillis() < until);
            Thread.sleep(100);
        }
        js("S.onboarded=true;S.profile.role='user';document.getElementById('onboard').classList.add('hidden');"
                + "renderProfile();switchView('settings')");
        assertEquals("true", js("document.getElementById('testRemBtn').getBoundingClientRect().height===0"
                + " && document.getElementById('ttAdminTools').getBoundingClientRect().height===0"));
        assertEquals("true", js("document.documentElement.scrollWidth<=innerWidth+1"));
        screenshot("ux-normal-settings.png");

        js("document.getElementById('fab').click()");
        assertEquals("true", js("document.getElementById('editor').scrollHeight>document.getElementById('editor').clientHeight"));
        screenshot("ux-task-editor.png");
        js("document.getElementById('fDuePicker').click();document.querySelector('[data-dt-quick=tomorrow9]').click()" );
        assertEquals("true", js("!document.getElementById('dateTimeWrap').classList.contains('hidden')"
                + " && document.querySelectorAll('#dtDays button').length===42"));
        assertEquals("true", js("document.getElementById('dateTimeSheet').scrollWidth<=innerWidth+1"));
        screenshot("ux-themed-picker.png");
        js("document.getElementById('dtHour').value='14';document.getElementById('dtMinute').value='17';"
                + "document.getElementById('dtConfirm').click()");
        assertEquals("true", js("/T14:17$/.test(document.getElementById('fDue').value)"
                + " && document.getElementById('dateTimeWrap').classList.contains('hidden')"
                + " && !document.getElementById('editorWrap').classList.contains('hidden')"));

        // Real touchscreen scrolling must reach the save action without closing the form.
        float height = Float.parseFloat(js("innerHeight"));
        swipe(180, height - 145, 180, height * 0.28f);
        assertEquals("true", js("document.getElementById('editor').scrollTop>20"
                + " && !document.getElementById('editorWrap').classList.contains('hidden')"));
        screenshot("ux-task-scrolled.png");
        js("document.getElementById('editor').scrollTop=0");
        dismissUpdateNotice();
        awaitVisual();
        Thread.sleep(350);
        float top = Float.parseFloat(js("document.getElementById('editor').getBoundingClientRect().top"));
        js("window.__sheetInput=[];window.__sheetHit=document.elementFromPoint(175," + (top + 12) + ").className;"
                + "['touchstart','touchmove','touchend','touchcancel'].forEach(name=>document.getElementById('editor')"
                + ".addEventListener(name,e=>{let p=e.touches[0]||e.changedTouches[0];window.__sheetInput.push({"
                + "type:name,cancelable:e.cancelable,x:p&&p.clientX,y:p&&p.clientY,target:e.target.className})},{capture:true,passive:true}))");
        swipe(175, top + 12, 175, top + 140);
        assertEquals("Real down swipe: " + js("JSON.stringify({hit:window.__sheetHit,events:window.__sheetInput,"
                + "scrollTop:document.getElementById('editor').scrollTop,gestureBound:document.getElementById('editor').dataset.gestureBound})"),
                "true", js("document.getElementById('editorWrap').classList.contains('hidden')"));
    }
}
