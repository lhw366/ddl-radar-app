package com.ddlradar.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
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
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().resumeTimers();
            bridge.getWebView().onResume();
        }
    }

    @Override public void onStop() {
        if (bridge != null && bridge.getWebView() != null) {
            // Native alarms and native overlay windows do not use WebView timers.
            bridge.getWebView().onPause();
            bridge.getWebView().pauseTimers();
        }
        super.onStop();
    }
}
