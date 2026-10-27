package com.ddlradar.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static volatile boolean visible;
    static volatile long lastForegroundElapsed;
    static volatile long lastBackgroundElapsed;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ApkInstallerPlugin.class);
        registerPlugin(DdlNotifyPlugin.class);
        registerPlugin(DdlAlarmPlugin.class);
        registerPlugin(DdlOcrPlugin.class);
        super.onCreate(savedInstanceState);
        // Future reminders are owned by AlarmManager; no idle foreground service.
    }

    @Override public void onStart() {
        super.onStart();
        visible = true;
        lastForegroundElapsed = android.os.SystemClock.elapsedRealtime();
        getSharedPreferences("ddlr_lifecycle", MODE_PRIVATE).edit()
                .putLong("lastForegroundAt", System.currentTimeMillis()).apply();
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().resumeTimers();
            bridge.getWebView().onResume();
        }
    }

    @Override public void onStop() {
        AlarmService.planChanged(this);
        visible = false;
        lastBackgroundElapsed = android.os.SystemClock.elapsedRealtime();
        getSharedPreferences("ddlr_lifecycle", MODE_PRIVATE).edit()
                .putLong("lastBackgroundAt", System.currentTimeMillis()).apply();
        if (bridge != null && bridge.getWebView() != null) {
            // Native alarms and native overlay windows do not use WebView timers.
            bridge.getWebView().onPause();
            // Do not globally pause Chromium: pending bridge acknowledgements must drain.
        }
        super.onStop();
        getSharedPreferences("ddlr_lifecycle", MODE_PRIVATE).edit()
                .putLong("lastBackgroundCompletedAt", System.currentTimeMillis()).apply();
    }
}
