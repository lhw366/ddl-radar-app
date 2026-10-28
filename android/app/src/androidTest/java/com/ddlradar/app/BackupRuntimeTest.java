package com.ddlradar.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercise the Release JS bridge and Activity result, with a local test destination. */
@RunWith(AndroidJUnit4.class)
public class BackupRuntimeTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    private Context context;
    private Instrumentation.ActivityMonitor monitor;
    private File destination;

    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(
                script, value -> { result.set(value); done.countDown(); }));
        assertTrue("WebView script timed out", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    @Before public void openApp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = System.currentTimeMillis() + 30000;
        while (!"true".equals(js("!!(window.S && typeof exportBackup==='function' && Capacitor.Plugins.DdlBackup)"))) {
            assertTrue("Application did not initialize the backup bridge", System.currentTimeMillis() < until);
            Thread.sleep(100);
        }
        js("window.__backupOldState=JSON.stringify(S);S.onboarded=true;"
                + "document.getElementById('onboard').classList.add('hidden');"
                + "document.getElementById('toastWrap').innerHTML='';"
                + "if(document.getElementById('updateNotice'))document.getElementById('updateNotice').remove();"
                + "S.tasks=[];S.timetable={courses:[]};switchView('settings')");
    }

    @After public void cleanUp() throws Exception {
        if (monitor != null) instrumentation.removeMonitor(monitor);
        if (destination != null) destination.delete();
        if (activity != null) js("if(window.__backupOldState){S=normalizeState(JSON.parse(window.__backupOldState));"
                + "window.S=S;bindSettingsValues();delete window.__backupOldState}");
    }

    private void returnFromPicker(int resultCode, Uri uri) throws Exception {
        IntentFilter filter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("application/json");
        Intent data = uri == null ? null : new Intent().setData(uri);
        monitor = instrumentation.addMonitor(filter, new Instrumentation.ActivityResult(resultCode, data), true);
    }

    private void waitForExport() throws Exception {
        long until = System.currentTimeMillis() + 20000;
        while (!"false".equals(js("backupExportBusy"))) {
            assertTrue("Backup export did not finish", System.currentTimeMillis() < until);
            Thread.sleep(50);
        }
        assertEquals("Both backup buttons are usable after completion", "true", js(
                "!document.getElementById('exportBtn').disabled && !document.getElementById('saveRecoveryBtn').disabled"));
        assertEquals("The native bridge opened ACTION_CREATE_DOCUMENT", 1, monitor.getHits());
    }

    private int pendingSnapshots() {
        File[] files = context.getCacheDir().listFiles((dir, name) -> name.startsWith("ddlr-backup-") && name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    private void assertSnapshotsReleased(int before) throws Exception {
        long until = System.currentTimeMillis() + 3000;
        while (pendingSnapshots() > before && System.currentTimeMillis() < until) Thread.sleep(30);
        assertEquals("The private snapshot is released after completion or cancellation", before, pendingSnapshots());
    }

    @Test public void localDocumentExportWritesExactUtf8JsonIncludingWallpaper() throws Exception {
        int before = pendingSnapshots();
        destination = new File(context.getCacheDir(), "backup-runtime-readback.json");
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", destination);
        returnFromPicker(Activity.RESULT_OK, uri);
        js("S.profile.name='中文恢复测试';"
                + "S.settings.bgImage='data:image/png;base64,'+'A'.repeat(1280000);"
                + "window.__backupExpected=JSON.stringify(S,null,2);"
                + "document.getElementById('saveRecoveryBtn').click()");
        waitForExport();
        assertEquals("true", js("document.getElementById('toastWrap').textContent.includes('恢复包已保存')"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        String actual = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("The full wallpaper is exported", actual.length() > 1280000);
        assertEquals(new org.json.JSONTokener(js("window.__backupExpected")).nextValue(), actual);
        org.json.JSONObject restored = new org.json.JSONObject(actual);
        assertEquals("中文恢复测试", restored.getJSONObject("profile").getString("name"));
        assertSnapshotsReleased(before);
    }

    @Test public void cancellingSystemSaveDoesNotClaimSuccessOrChangeState() throws Exception {
        int before = pendingSnapshots();
        String original = js("JSON.stringify(S)");
        returnFromPicker(Activity.RESULT_CANCELED, null);
        js("document.getElementById('saveRecoveryBtn').click()");
        waitForExport();
        assertEquals(original, js("JSON.stringify(S)"));
        assertEquals("true", js("document.getElementById('toastWrap').textContent.includes('取消保存')"
                + " && !document.getElementById('toastWrap').textContent.includes('已保存')"));
        assertSnapshotsReleased(before);
    }

    @Test public void aProviderWriteFailureIsReportedAndDoesNotPretendToDownload() throws Exception {
        int before = pendingSnapshots();
        returnFromPicker(Activity.RESULT_OK, Uri.parse("content://com.ddlradar.app.missing-provider/backup"));
        js("document.getElementById('exportBtn').click()");
        waitForExport();
        assertEquals("true", js("document.getElementById('toastWrap').textContent.includes('未保存')"
                + " && !document.getElementById('toastWrap').textContent.includes('发起恢复包下载')"));
        assertSnapshotsReleased(before);
    }

    @Test public void documentPickerIsLimitedToUserChosenLocalJson() {
        Intent intent = DdlBackupPlugin.createDocumentIntent("backup.json");
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertEquals("application/json", intent.getType());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false));
        assertEquals("backup.json", intent.getStringExtra(Intent.EXTRA_TITLE));
    }
}
