package com.ddlradar.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内直接更新：下载 APK 到应用外部缓存目录，完成后拉起系统安装确认。
 * Android 不允许任何应用静默替换自己，最后一步的系统"安装"确认无法省略。
 */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstallerPlugin extends Plugin {

    private PluginCall downloadingCall;

    @PluginMethod
    public void installApk(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("url required"); return; }
        downloadingCall = call;

        new Thread(() -> {
            try {
                File dir = getContext().getExternalFilesDir("update");
                if (dir == null) { rejectOnUi("storage unavailable"); return; }
                if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "ddl-radar-update.apk");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) { rejectOnUi("http " + code); return; }

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[8192];
                int n; long total = 0;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                out.flush(); out.close(); in.close();

                notifyListeners("downloaded", new JSObject().put("bytes", total));
                openInstaller(apk);
                resolveOnUi(new JSObject().put("bytes", total).put("path", apk.getAbsolutePath()));
            } catch (Exception e) {
                rejectOnUi(e.getMessage() == null ? "download failed" : e.getMessage());
            }
        }, "apk-download").start();
    }

    private void openInstaller(File apk) {
        Context ctx = getContext();
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private void resolveOnUi(JSObject data) {
        if (downloadingCall != null) {
            var c = downloadingCall; downloadingCall = null;
            getBridge().executeOnMainThread(() -> c.resolve(data));
        }
    }

    private void rejectOnUi(String msg) {
        if (downloadingCall != null) {
            var c = downloadingCall; downloadingCall = null;
            getBridge().executeOnMainThread(() -> c.reject(msg));
        }
    }
}
