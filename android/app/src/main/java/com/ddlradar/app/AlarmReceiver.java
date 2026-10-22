package com.ddlradar.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 闹钟投递的广播入口。
 *
 * O+ 正常情况下不走这里（PendingIntent 直接 getForegroundService 拉起
 * AlarmService）；本接收器覆盖两类场景：
 * 1. Android 8.0 以下没有前台服务机制，用 startService 委托；
 * 2. 万一系统把 FGS 拉起降级为广播（个别 ROM 行为），也能兜住。
 *
 * 注意 Android 8+ 后台直接 startService 会抛 IllegalStateException
 * （BackgroundServiceStartNotAllowedException），必须用 startForegroundService。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (intent.getIntExtra("id", -1) == -1) return;
        Intent svc = new Intent(context, AlarmService.class);
        svc.putExtras(intent);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            // 系统限制后台启动时不要崩溃：闹钟记录仍在，App 下次打开会重排
            AlarmScheduler.markFired(context, intent.getIntExtra("id", -1), "blocked");
        }
    }
}
