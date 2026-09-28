package com.ddlradar.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ApkInstallerPlugin.class);
        registerPlugin(DdlNotifyPlugin.class);
        registerPlugin(DdlAlarmPlugin.class);
        super.onCreate(savedInstanceState);
        // 提醒守护前台服务随 App 启动常驻：进程不被冻结，后台闹钟投递才可靠
        AlarmService.start(this);
    }
}
