package com.ddlradar.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-initiated, local document export. No broad storage grant or network access. */
@CapacitorPlugin(name = "DdlBackup")
public class DdlBackupPlugin extends Plugin {
    private static final String SNAPSHOT_PREFIX = "ddlr-backup-";
    private final AtomicBoolean exporting = new AtomicBoolean();

    @PluginMethod
    public void exportJson(PluginCall call) {
        if (!exporting.compareAndSet(false, true)) {
            call.reject("已有恢复包正在保存，请先完成或取消。");
            return;
        }
        final String json = call.getString("json");
        final String filename = safeFilename(call.getString("filename"));
        try {
            if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException();
            new JSONObject(json);
        } catch (Exception invalid) {
            exporting.set(false);
            call.reject("恢复包内容不是有效的 JSON。");
            return;
        }

        // Keep the large snapshot (including a wallpaper) out of the Activity's
        // saved-state Bundle. Capacitor retains only this private cache basename
        // while the system document picker is open, including process recreation.
        new Thread(() -> {
            File snapshot = null;
            try {
                snapshot = File.createTempFile(SNAPSHOT_PREFIX, ".json", getContext().getCacheDir());
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(snapshot), StandardCharsets.UTF_8)) {
                    writer.write(json);
                }
                call.getData().remove("json");
                call.getData().put("filename", filename);
                call.getData().put("snapshotName", snapshot.getName());
                getActivity().runOnUiThread(() -> {
                    try {
                        startActivityForResult(call, createDocumentIntent(filename), "documentCreated");
                    } catch (Exception unavailable) {
                        discardSnapshot(call);
                        exporting.set(false);
                        call.reject("无法打开系统保存窗口，请稍后重试。");
                    }
                });
            } catch (Exception failed) {
                if (snapshot != null) snapshot.delete();
                exporting.set(false);
                call.reject("无法准备恢复包，请检查手机可用空间。");
            }
        }, "DdlBackupPrepare").start();
    }

    static Intent createDocumentIntent(String filename) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, safeFilename(filename))
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
    }

    @ActivityCallback
    private void documentCreated(PluginCall call, ActivityResult result) {
        if (call == null) {
            exporting.set(false);
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK) {
            discardSnapshot(call);
            exporting.set(false);
            call.resolve(new JSObject().put("saved", false).put("cancelled", true));
            return;
        }
        final Uri uri = result.getData() == null ? null : result.getData().getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            discardSnapshot(call);
            exporting.set(false);
            call.reject("系统没有返回可写入的文件。");
            return;
        }
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                File snapshot = snapshotFile(call);
                long bytes = writeToUri(context, snapshot, uri);
                String name = displayName(context, uri, safeFilename(call.getString("filename")));
                call.resolve(new JSObject().put("saved", true).put("cancelled", false)
                        .put("filename", name).put("bytes", bytes));
            } catch (Exception failed) {
                call.reject("恢复包写入失败，请确认位置可写且有足够空间，再重试。");
            } finally {
                discardSnapshot(call);
                exporting.set(false);
            }
        }, "DdlBackupWrite").start();
    }

    /** Both streams close successfully before an export can report saved=true. */
    static long writeToUri(Context context, File snapshot, Uri destination) throws IOException {
        long bytes = 0;
        try (FileInputStream input = new FileInputStream(snapshot);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("No output stream");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                bytes += count;
            }
            output.flush();
        }
        return bytes;
    }

    private File snapshotFile(PluginCall call) throws IOException {
        String name = call.getString("snapshotName");
        if (name == null || !name.startsWith(SNAPSHOT_PREFIX) || !name.endsWith(".json") ||
                !new File(name).getName().equals(name)) throw new IOException("Invalid snapshot");
        File file = new File(getContext().getCacheDir(), name);
        if (!file.getCanonicalFile().getParentFile().equals(getContext().getCacheDir().getCanonicalFile()))
            throw new IOException("Invalid snapshot location");
        return file;
    }

    private void discardSnapshot(PluginCall call) {
        try { snapshotFile(call).delete(); } catch (Exception ignored) { /* Best-effort private cache cleanup. */ }
    }

    private static String displayName(Context context, Uri uri, String fallback) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { /* A document provider may omit display metadata. */ }
        return fallback;
    }

    private static String safeFilename(String name) {
        if (name == null) return "ddlradar-backup.json";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) return "ddlradar-backup.json";
        if (name.length() > 100) name = name.substring(0, 100);
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".json") ? name : name + ".json";
    }
}
