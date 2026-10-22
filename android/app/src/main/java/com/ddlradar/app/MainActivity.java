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
}
