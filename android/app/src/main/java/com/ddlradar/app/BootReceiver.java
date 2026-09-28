package com.ddlradar.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统事件恢复接收器：AlarmManager 的闹钟不跨重启、也不跨应用替换安装，
 * 这三种事件后用持久化列表（AlarmScheduler.reapplyAll）原样恢复未来闹钟：
 * - BOOT_COMPLETED：开机完成
 * - TIME_SET：用户手动改系统时间（RTC 墙钟闹钟需重排保持正确）
 * - MY_PACKAGE_REPLACED：应用内一键更新覆盖安装后（系统会清掉全部闹钟）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.reapplyAll(context);
        }
    }
}
