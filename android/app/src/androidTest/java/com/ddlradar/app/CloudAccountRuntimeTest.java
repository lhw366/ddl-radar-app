package com.ddlradar.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Release bridge and AndroidKeyStore tests. No email is sent or real user created. */
@RunWith(AndroidJUnit4.class)
public class CloudAccountRuntimeTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Context context;
    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(
                script, value -> { result.set(value); done.countDown(); }));
        assertTrue(done.await(15, TimeUnit.SECONDS)); return result.get();
    }
    private String awaitResult(String operation) throws Exception {
        js("window.__secureResult=null;(async()=>{try{window.__secureResult=(await(" + operation
                + "))??{ok:true};}catch(e){window.__secureResult={rejected:true}}})()");
        long until = System.currentTimeMillis() + 15000;
        String result;
        while ("null".equals(result = js("window.__secureResult"))) {
            assertTrue("Secure bridge did not finish", System.currentTimeMillis() < until); Thread.sleep(50);
        }
        return result;
    }
    @Before public void start() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = System.currentTimeMillis() + 30000;
        while (!"true".equals(js("!!(window.CloudAccount&&window.DdlSupabase&&window.S&&Capacitor.Plugins.DdlSecureStore"
                + "&&document.getElementById('editor').dataset.gestureBound==='1')"))) {
            assertTrue(System.currentTimeMillis() < until); Thread.sleep(100);
        }
    }
    @Test public void authSecretsRoundTripEncryptedOutsideBackupsWithDifferentNonces() throws Exception {
        File encrypted = new File(context.getNoBackupFilesDir(), "ddlr-auth-test.enc");
        String command = "Capacitor.Plugins.DdlSecureStore.set({key:'ddlr-auth-test',value:'not-a-real-token-12345'}).then(()=>({ok:true}))";
        assertEquals("{\"ok\":true}", awaitResult(command));
        String first = new String(Files.readAllBytes(encrypted.toPath()), StandardCharsets.UTF_8);
        assertFalse(first.contains("not-a-real-token-12345")); assertTrue(first.contains("iv"));
        assertEquals("{\"value\":\"not-a-real-token-12345\"}", awaitResult("Capacitor.Plugins.DdlSecureStore.get({key:'ddlr-auth-test'})"));
        assertEquals("{\"ok\":true}", awaitResult(command));
        assertNotEquals(first, new String(Files.readAllBytes(encrypted.toPath()), StandardCharsets.UTF_8));
        assertEquals("null", js("localStorage.getItem('ddlr-auth-test')"));
        awaitResult("Capacitor.Plugins.DdlSecureStore.remove({key:'ddlr-auth-test'}).then(()=>({ok:true}))");
        assertFalse(encrypted.exists());
    }
    @Test public void tamperedCiphertextAndPathTraversalAreRejectedWithoutPlaintextFallback() throws Exception {
        File encrypted = new File(context.getNoBackupFilesDir(), "ddlr-auth-tamper.enc");
        awaitResult("Capacitor.Plugins.DdlSecureStore.set({key:'ddlr-auth-tamper',value:'test-only'})");
        try (FileOutputStream out = new FileOutputStream(encrypted)) { out.write("{\"iv\":\"AAAA\",\"data\":\"AAAA\"}".getBytes(StandardCharsets.UTF_8)); }
        assertEquals("{\"rejected\":true}", awaitResult("Capacitor.Plugins.DdlSecureStore.get({key:'ddlr-auth-tamper'})"));
        assertEquals("{\"rejected\":true}", awaitResult("Capacitor.Plugins.DdlSecureStore.set({key:'../unsafe',value:'x'})"));
        awaitResult("Capacitor.Plugins.DdlSecureStore.remove({key:'ddlr-auth-tamper'}).then(()=>({ok:true}))");
    }
    @Test public void accountFormFitsThemesAndNativeEmailCallbackResolves() throws Exception {
        Intent callback = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("ddlradar://auth/callback?code=test-only"));
        callback.setPackage(context.getPackageName());
        assertNotNull(context.getPackageManager().resolveActivity(callback, 0));
        js("S.onboarded=true;document.getElementById('onboard').classList.add('hidden');switchView('settings');"
                + "if(document.getElementById('updateLater'))document.getElementById('updateLater').click()");
        String originalTheme = js("S.settings.theme");
        for (String theme : new String[]{"cat", "tech", "cloud", "soda"}) {
            js("S.settings.theme='" + theme + "';applyTheme();window.scrollTo(0,0)");
            assertEquals("true", js("document.documentElement.scrollWidth<=innerWidth+1"));
            js("document.querySelector('[data-cloud-mode=signup]').click()");
            assertEquals("\"new-password\"", js("document.getElementById('cloudPassword').autocomplete"));
            js("document.querySelector('[data-cloud-mode=reset]').click()");
            assertEquals("true", js("document.getElementById('cloudPasswordRow').hidden"));
            js("document.querySelector('[data-cloud-mode=login]').click()");
            instrumentation.waitForIdleSync(); Thread.sleep(400);
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(bitmap);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "cloud-account-" + theme + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally { bitmap.recycle(); }
        }
        js("S.settings.theme=" + originalTheme + ";applyTheme()");
    }
}
