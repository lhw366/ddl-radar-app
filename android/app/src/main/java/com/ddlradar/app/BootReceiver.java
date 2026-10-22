package com.ddlradar.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统事件恢复接收器：AlarmManager 的闹钟不跨重启、也不跨应用替换安装，
 * 这些事件后用持久化列表（AlarmScheduler.reapplyAll）原样恢复未来闹钟：
 * - BOOT_COMPLETED：开机完成
 * - LOCKED_BOOT_COMPLETED：直接启动（未解锁）时也要恢复，否则解锁前无闹钟
 * - TIME_SET / TIME_CHANGED：用户手动改系统时间（RTC 墙钟闹钟需重排保持正确）
 * - TIMEZONE_CHANGED：改时区，墙钟时刻对应的时间戳变了，必须重排
 * - MY_PACKAGE_REPLACED：应用内一键更新覆盖安装后（系统会清掉全部闹钟）
 *
 * 注意：这些是系统广播，receiver 必须 android:exported="true"，
 * 否则 Android 12+ 上收不到（早前写成 false，导致开机/更新后闹钟不恢复）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        boolean hit = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!hit) return;
        try {
            AlarmScheduler.reapplyAll(context);
        } catch (Exception e) { /* 恢复失败不影响后续守护服务启动 */ }
        try {
            AlarmService.start(context); // 守护服务随开机/更新恢复常驻
        } catch (Exception e) { /* Android 12+ 后台启动 FGS 受限时忽略，下次打开 App 会补 */ }
    }
}
