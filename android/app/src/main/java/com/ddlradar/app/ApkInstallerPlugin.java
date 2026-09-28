package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用内直接更新：下载 APK 到应用外部缓存目录，完成后拉起系统安装确认。
 * Android 不允许任何应用静默替换自己，最后一步的系统"安装"确认无法省略。
 *
 * 下载走多线路：JS 传入 urls（jsdelivr 镜像优先、github 直连兜底），
 * 逐条尝试（连接 10s / 读取 30s 超时），下载完校验 ZIP 头与体积，
 * 防止把超时页/错误页当安装包；一条失败自动切下一条。
 */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstallerPlugin extends Plugin {

    private PluginCall downloadingCall;

    @PluginMethod
    public void installApk(PluginCall call) {
        List<String> urls = new ArrayList<>();
        JSArray arr = call.getArray("urls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, null);
                if (u != null && !u.isEmpty() && !urls.contains(u)) urls.add(u);
            }
        }
        String single = call.getString("url");
        if (single != null && !single.isEmpty() && !urls.contains(single)) urls.add(single);
        if (urls.isEmpty()) { call.reject("url required"); return; }
        downloadingCall = call;

        new Thread(() -> {
            File dir = getContext().getExternalFilesDir("update");
            if (dir == null) { rejectOnUi("storage unavailable"); return; }
            if (!dir.exists()) dir.mkdirs();
            File apk = new File(dir, "ddl-radar-update.apk");

            Exception lastErr = null;
            StringBuilder tried = new StringBuilder();
            for (String u : urls) {
                try {
                    long bytes = downloadTo(u, apk);
                    notifyListeners("downloaded", new JSObject().put("bytes", bytes));
                    openInstaller(apk);
                    resolveOnUi(new JSObject().put("bytes", bytes).put("path", apk.getAbsolutePath()));
                    return;
                } catch (Exception e) {
                    lastErr = e;
                    String host = u.split("/", 4).length > 3 ? u.split("/", 4)[2] : u;
                    tried.append(host).append(": ")
                            .append(e.getMessage() == null ? "failed" : e.getMessage()).append("; ");
                }
            }
            String detail = tried.toString();
            if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
            rejectOnUi("已试 " + urls.size() + " 条线路：" + detail
                    + "（最后错误：" + (lastErr == null ? "unknown" : lastErr.getMessage()) + "）");
        }, "apk-download").start();
    }

    /** 下载单条线路并校验产物；失败抛异常由上层切下一条 */
    private long downloadTo(String url, File apk) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("http " + code);

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(apk);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            out.flush();
            out.close();
            in.close();
        } finally {
            conn.disconnect();
        }

        // 完整性校验：体积 > 1MB 且以 ZIP 头（PK）开始，防止把错误页当安装包
        if (apk.length() < 1024 * 1024) throw new Exception("file too small (" + apk.length() + "B)");
        RandomAccessFile raf = new RandomAccessFile(apk, "r");
        try {
            byte[] head = new byte[2];
            raf.readFully(head);
            if (head[0] != 'P' || head[1] != 'K') throw new Exception("not an apk (bad header)");
        } finally {
            raf.close();
        }
        return apk.length();
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
            PluginCall c = downloadingCall;
            downloadingCall = null;
            getBridge().executeOnMainThread(() -> c.resolve(data));
        }
    }

    private void rejectOnUi(String msg) {
        if (downloadingCall != null) {
            PluginCall c = downloadingCall;
            downloadingCall = null;
            getBridge().executeOnMainThread(() -> c.reject(msg));
        }
    }
}
