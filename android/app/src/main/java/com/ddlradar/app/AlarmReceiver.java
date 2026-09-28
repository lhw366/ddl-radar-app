package com.ddlradar.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android 8.0 以下的降级通道：闹钟广播到达后转 startService 交给 AlarmService
 * 统一处理（O+ 的闹钟 PendingIntent 直接指向前台服务，不经过本接收器）。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getIntExtra("id", -1) == -1) return;
        Intent svc = new Intent(context, AlarmService.class);
        svc.putExtras(intent);
        context.startService(svc);
    }
}
