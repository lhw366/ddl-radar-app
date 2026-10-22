package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 下载和安装是两个阶段：授权/安装取消后复用通过校验的同一构建 APK。 */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstallerPlugin extends Plugin {
    // Plugin/WebView 重建不能产生第二个下载器；回调属于各自请求，绝不共享或覆盖。
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<ApkInstallerPlugin> current = new WeakReference<>(null);
    private static boolean loaded, busy;
    private static int targetCode;
    private static String status = "idle", error = "";
    private static List<String> targetUrls = new ArrayList<>();
    private static long bytes, total, lastLaunchAt;
    private static final String PREFS = "ddlr_update";

    @Override
    public void load() {
        synchronized (LOCK) { current = new WeakReference<>(this); }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureState(Context ctx) {
        if (loaded) return;
        SharedPreferences p = prefs(ctx);
        targetCode = p.getInt("code", 0);
        status = p.getString("status", "idle");
        error = p.getString("error", "");
        targetUrls = new ArrayList<>();
        try {
            JSArray saved = new JSArray(p.getString("urls", "[]"));
            for (int i = 0; i < saved.length(); i++) targetUrls.add(saved.getString(i));
        } catch (Exception ignored) { }
        loaded = true;
        refreshState(ctx);
    }

    private static void saveState(Context ctx) {
        prefs(ctx).edit().putInt("code", targetCode).putString("status", status)
                .putString("urls", new JSArray(targetUrls).toString()).putString("error", error).apply();
    }

    private static File apkFile(Context ctx, int code) {
        File dir = ctx.getExternalFilesDir("update");
        if (dir == null) dir = new File(ctx.getCacheDir(), "update");
        return new File(dir, "ddl-radar-update-" + code + ".apk");
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static long installedCode(Context ctx) throws Exception {
        return versionCode(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0));
    }

    private static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static void validateApk(Context ctx, File apk, int code) throws Exception {
        if (!apk.isFile() || apk.length() < 1024 * 1024) throw new Exception("安装包不完整");
        PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || !ctx.getPackageName().equals(info.packageName)) throw new Exception("安装包无效或应用不匹配");
        if (versionCode(info) != code) throw new Exception("安装包构建号不匹配（期望 " + code + "）");
    }

    private static boolean hasApk(Context ctx, int code) {
        try { validateApk(ctx, apkFile(ctx, code), code); return true; }
        catch (Exception ignored) { return false; }
    }

    // 进程被回收时 downloading 不能永久锁住按钮；只复用完整且版本匹配的 .apk。
    private static void refreshState(Context ctx) {
        if (busy || targetCode <= 0) return;
        try {
            if (targetCode <= installedCode(ctx)) {
                status = "installed";
                error = "";
            } else if (hasApk(ctx, targetCode)) {
                bytes = total = apkFile(ctx, targetCode).length();
                if (!"installerOpened".equals(status)) status = "ready";
            } else if (!"error".equals(status)) {
                status = "available";
            }
            if (!"installed".equals(status) && "awaitingPermission".equals(prefs(ctx).getString("status", "")) && !canInstall(ctx)) {
                status = "awaitingPermission";
            }
            saveState(ctx);
        } catch (Exception e) {
            error = e.getMessage();
            status = "error";
        }
    }

    private static JSObject snapshot(Context ctx) {
        return new JSObject().put("status", status).put("code", targetCode)
                .put("urls", new JSArray(targetUrls)).put("bytes", bytes).put("total", total)
                .put("busy", busy).put("canInstall", canInstall(ctx)).put("error", error);
    }

    private static void emit(String event, JSObject data) {
        MAIN.post(() -> {
            ApkInstallerPlugin plugin;
            synchronized (LOCK) { plugin = current.get(); }
            if (plugin != null) {
                try { plugin.notifyListeners(event, data); } catch (Exception ignored) { }
            }
        });
    }

    @PluginMethod
    public void getUpdateState(PluginCall call) {
        Context ctx = getContext().getApplicationContext();
        synchronized (LOCK) {
            ensureState(ctx);
            refreshState(ctx);
            call.resolve(snapshot(ctx));
        }
    }

    @PluginMethod
    public void installApk(PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        List<String> urls = new ArrayList<>();
        JSArray arr = call.getArray("urls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (u.startsWith("https://") && !urls.contains(u)) urls.add(u);
            }
        }
        String single = call.getString("url", "");
        if (single.startsWith("https://") && !urls.contains(single)) urls.add(single);
        int code = call.getInt("versionCode", 0);
        // 兼容尚未重载的旧前端，同时仍按构建号缓存，不能缓存一个固定文件名。
        if (code <= 0) {
            for (String u : urls) {
                Matcher m = Pattern.compile("ddl-radar-(\\d+)\\.apk(?:[?#].*)?$").matcher(u);
                if (m.find()) {
                    try { code = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { }
                    if (code > 0) break;
                }
            }
        }
        synchronized (LOCK) {
            ensureState(ctx);
            if (busy) { call.resolve(snapshot(ctx)); return; }
            if (code <= 0) code = targetCode;
            if (urls.isEmpty() && code == targetCode) urls = new ArrayList<>(targetUrls);
            if (code <= 0 || urls.isEmpty()) { call.reject("缺少更新构建号或下载地址"); return; }
            // 系统界面刚打开时的排队点击也不能再次弹安装器/设置页。
            if (code == targetCode && System.currentTimeMillis() - lastLaunchAt < 1500) {
                call.resolve(snapshot(ctx)); return;
            }
            targetCode = code;
            targetUrls = new ArrayList<>(urls);
            busy = true;
            bytes = total = 0;
            error = "";
            status = "preparing";
            saveState(ctx);
        }
        final int requestedCode = code;
        final List<String> requestedUrls = urls;
        new Thread(() -> {
            try {
                if (requestedCode <= installedCode(ctx)) {
                    finish(ctx, call, "installed", null);
                    return;
                }
                // 在下载前请求授权。返回后继续同一版本，不会重复下载。
                if (!canInstall(ctx)) { openSystemUi(ctx, call, requestedCode, false); return; }
                File apk = apkFile(ctx, requestedCode);
                boolean reused = hasApk(ctx, requestedCode);
                if (!reused) {
                    File dir = apk.getParentFile();
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("更新目录不可用");
                    synchronized (LOCK) { status = "downloading"; saveState(ctx); emit("stateChanged", snapshot(ctx)); }
                    File part = new File(dir, apk.getName() + ".part");
                    StringBuilder failures = new StringBuilder();
                    boolean downloaded = false;
                    for (String url : requestedUrls) {
                        try {
                            downloadTo(url, part, requestedCode);
                            validateApk(ctx, part, requestedCode);
                            if (apk.exists() && !apk.delete()) throw new Exception("无法替换旧缓存");
                            if (!part.renameTo(apk)) throw new Exception("无法保存完整安装包");
                            downloaded = true;
                            break;
                        } catch (Exception e) {
                            part.delete();
                            failures.append(new URL(url).getHost()).append(": ").append(e.getMessage()).append("; ");
                        }
                    }
                    if (!downloaded) throw new Exception("下载失败：" + failures.substring(0, Math.min(200, failures.length())));
                }
                synchronized (LOCK) {
                    bytes = total = apk.length();
                    status = "ready";
                    saveState(ctx);
                    emit("downloaded", snapshot(ctx).put("reused", reused));
                }
                openSystemUi(ctx, call, requestedCode, true);
            } catch (Exception e) {
                finish(ctx, call, "error", e.getMessage() == null ? "更新失败，请重试" : e.getMessage());
            }
        }, "apk-download").start();
    }

    private void downloadTo(String url, File part, int code) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int response = conn.getResponseCode();
            if (response < 200 || response >= 300) throw new Exception("HTTP " + response);
            long expected = conn.getContentLength();
            long got = 0, reported = 0;
            synchronized (LOCK) { bytes = 0; total = Math.max(0, expected); }
            emit("progress", new JSObject().put("code", code).put("bytes", 0).put("total", Math.max(0, expected)));
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (LOCK) { bytes = got; }
                    if (got - reported >= 512 * 1024) {
                        reported = got;
                        emit("progress", new JSObject().put("code", code).put("bytes", got).put("total", Math.max(0, expected)));
                    }
                }
                out.flush();
                if (expected >= 0 && got != expected) throw new Exception("下载中断（文件长度不符）");
            }
        } finally { conn.disconnect(); }
    }

    private void openSystemUi(Context ctx, PluginCall call, int code, boolean downloaded) {
        MAIN.post(() -> {
            try {
                boolean allowed = canInstall(ctx);
                Intent intent;
                if (!allowed) {
                    intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                } else if (downloaded) {
                    File apk = apkFile(ctx, code);
                    validateApk(ctx, apk, code);
                    Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
                    intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    // 授权恰在检查与进入主线程之间变化，用户再次点即可继续。
                    finish(ctx, call, "available", null);
                    return;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                synchronized (LOCK) { lastLaunchAt = System.currentTimeMillis(); }
                finish(ctx, call, allowed ? "installerOpened" : "awaitingPermission", null);
            } catch (Exception e) {
                finish(ctx, call, "error", "打开安装或授权界面失败：" + e.getMessage());
            }
        });
    }

    private static void finish(Context ctx, PluginCall call, String nextStatus, String failure) {
        MAIN.post(() -> {
            JSObject data;
            synchronized (LOCK) {
                status = nextStatus;
                error = failure == null ? "" : failure;
                busy = false;
                saveState(ctx);
                data = snapshot(ctx);
            }
            emit("stateChanged", data);
            if (failure == null) call.resolve(data);
            else call.reject(failure, "UPDATE_FAILED", data);
        });
    }
}
