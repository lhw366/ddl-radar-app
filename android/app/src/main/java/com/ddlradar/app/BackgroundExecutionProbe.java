package com.ddlradar.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One observation per explicit test. Does not wake the device or deliver a reminder. */
final class BackgroundExecutionProbe {
    private static final String PREFS = "ddlr_background_probe";
    private static ScheduledThreadPoolExecutor executor;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static synchronized JSONObject arm(Context context, long at) throws Exception {
        long now = System.currentTimeMillis();
        if (at <= now || at > now + 120000L) throw new IllegalArgumentException("Probe must be within 120 seconds");
        if (executor != null) executor.shutdownNow();
        Context app = context.getApplicationContext();
        long delay = at - now;
        JSONObject state = new JSONObject().put("expectedAt", at).put("armedAt", now)
                .put("armedPid", android.os.Process.myPid())
                .put("expectedElapsed", SystemClock.elapsedRealtime() + delay)
                .put("expectedUptime", SystemClock.uptimeMillis() + delay)
                .put("finished", false);
        save(app, state);
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ddlr-one-shot-probe");
            thread.setDaemon(true);
            return thread;
        });
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor = worker;
        worker.schedule(() -> {
            try { observe(app, at); }
            finally { worker.shutdown(); }
        }, Math.max(0L, at - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        return state;
    }

    private static synchronized void observe(Context context, long at) {
        JSONObject state = read(context);
        if (state.optLong("expectedAt") != at) return;
        try {
            long now = System.currentTimeMillis();
            Thread ui = Looper.getMainLooper().getThread();
            JSONArray stack = new JSONArray();
            StackTraceElement[] frames = ui.getStackTrace();
            for (int i = 0; i < Math.min(10, frames.length); i++) stack.put(frames[i].toString());
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            state.put("workerAt", now).put("workerElapsed", SystemClock.elapsedRealtime())
                    .put("workerUptime", SystemClock.uptimeMillis()).put("workerAppVisible", MainActivity.visible)
                    .put("workerInteractive", power != null && power.isInteractive())
                    .put("mainThreadState", ui.getState().name()).put("mainStack", stack);
            save(context, state);
            // An independent main-queue acknowledgement separates an available
            // process from a blocked UI. No timeout loop or retry is started.
            MAIN.post(() -> acknowledge(context, at, now));
        } catch (Exception ignored) { }
    }

    private static synchronized void acknowledge(Context context, long at, long workerAt) {
        JSONObject state = read(context);
        if (state.optLong("expectedAt") != at) return;
        try {
            state.put("mainAt", System.currentTimeMillis()).put("mainAppVisible", MainActivity.visible)
                    .put("mainQueueDelayMs", Math.max(0, System.currentTimeMillis() - workerAt)).put("finished", true);
            save(context, state);
        } catch (Exception ignored) { }
    }

    static synchronized JSONObject read(Context context) {
        try { return new JSONObject(context.getSharedPreferences(PREFS, 0).getString("state", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static void save(Context context, JSONObject state) {
        context.getSharedPreferences(PREFS, 0).edit().putString("state", state.toString()).commit();
    }

    static synchronized boolean isWorkerRunning() {
        return executor != null && !executor.isTerminated();
    }

    private BackgroundExecutionProbe() { }
}
