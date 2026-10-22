package com.ddlradar.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

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
    /** 保护 downloadingCall 的并发判重（连点会触发多次 installApk） */
    private final Object downloadLock = new Object();

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

        // ⚠️ 并发保护：downloadingCall 是单个字段，两次并发 installApk 会互相覆盖，
        // 表现为「下载了多个安装包 / 进度乱跳 / 装的是上一个包」。
        // JS 侧已有 dataset.busy 防重入，这里再兜一层——因为插件可能被
        // 其他调用方（或旧版 JS 缓存）重复触发。
        synchronized (downloadLock) {
            if (downloadingCall != null) {
                call.reject("已有一个更新包正在下载，请稍候");
                return;
            }
            downloadingCall = call;
        }

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
                    // 同样必须在主线程发事件；失败不影响后续安装流程
                    final long doneBytes = bytes;
                    try {
                        getBridge().executeOnMainThread(() -> {
                            try {
                                notifyListeners("downloaded", new JSObject().put("bytes", doneBytes));
                            } catch (Exception ignore) {}
                        });
                    } catch (Exception ignore) {}
                    openInstaller(apk);
                    // openInstaller 内部投递到主线程；这里再投递一次 resolve。
                    // 两者都进主线程队列、先进先出，所以安装界面一定先弹出。
                    // 若 openInstaller 走了「缺权限」分支并 reject，downloadingCall
                    // 已被清空，下面这次 resolve 会自动跳过（不报错）。
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

    /** 下载单条线路并校验产物；每 512KB 上报进度；失败抛异常由上层切下一条 */
    private long downloadTo(String url, File apk) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("http " + code);
            long expected = conn.getContentLength();

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(apk);
            byte[] buf = new byte[16384];
            int n;
            long got = 0, reported = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (got - reported > 512 * 1024) {
                    reported = got;
                    // notifyListeners 必须在主线程调用。早前直接在下载线程里调，
                    // 会抛异常并中断整个下载——被外层 catch 当成「这条线路失败」，
                    // 于是每条线路都失败，最终表现为「手机上无法直接更新」。
                    // 这里改为主线程投递，且进度上报失败绝不影响下载本身。
                    final long g = got;
                    final long exp = Math.max(0, expected);
                    try {
                        getBridge().executeOnMainThread(() -> {
                            try {
                                notifyListeners("progress",
                                        new JSObject().put("bytes", g).put("total", exp));
                            } catch (Exception ignore) { /* 进度只是 UI，失败忽略 */ }
                        });
                    } catch (Exception ignore) { /* 桥不可用时忽略进度 */ }
                }
            }
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

    /**
     * 拉起系统安装界面。
     *
     * 注意两点（早前都有缺陷）：
     * 1) 必须在主线程调用 startActivity，下载线程直接调会抛
     *    RuntimeException("Can't create handler inside thread that has not called Looper.prepare()")。
     * 2) Android 8+ 需要「安装未知应用」权限；未授予时 ACTION_VIEW 会静默无反应，
     *    这里检测并跳转到授权页，否则用户会以为「点了没反应」。
     */
    private void openInstaller(File apk) {
        final Context ctx = getContext();
        getBridge().executeOnMainThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !ctx.getPackageManager().canRequestPackageInstalls()) {
                    Intent settings = new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(settings);
                    rejectOnUi("需要先允许「安装未知应用」，已跳转设置页。开启后请再点一次更新");
                    return;
                }
                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception e) {
                rejectOnUi("拉起安装界面失败：" + (e.getMessage() == null ? "unknown" : e.getMessage()));
            }
        });
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
