package com.ddlradar.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Notifications are posted before attempting any optional foreground service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (intent.getIntExtra("slot", 0) == 2) return; // Retired prewake route.
        if (intent.hasExtra("ackId")) {
            AlarmDelivery.cancelNotification(context, intent.getIntExtra("ackId", 0));
            ReminderBanner.dismiss(intent.getIntExtra("ackId", 0));
            return;
        }
        // 去重：同一条事件被多条通道重复送达时，只有第一条会真正执行效果。
        if (AlarmScheduler.wasDelivered(context, intent.getIntExtra("id", -1), intent.getLongExtra("at", 0))) {
            ReminderTrace.mark(context, intent.getIntExtra("id", -1), "receiver", "already_delivered");
            return;
        }
        AlarmScheduler.markReceiverHit(context, intent.getIntExtra("id", 0), "exact");
        ReminderTrace.mark(context, intent.getIntExtra("id", -1), "receiver", intent.getDataString());
        // ── 顺序很重要：先同步投递，再尝试前台服务 ──
        // 早前是「交给 startForegroundService 成功就 return」。问题是前台服务的启动
        // 与投递是两条独立的失败路径：Android 12+ 后台启动前台服务受限、进程刚被
        // 系统冻结、startForeground 抛异常 —— 任何一种都会让这次提醒彻底静默，
        // 既没有通知也没有人重试（闹钟已经响过了）。广播接收器执行期间系统持有
        // 唤醒锁，在这里同步完成通知 + 震动是有保证的。
        boolean delivered;
        try {
            delivered = AlarmDelivery.deliver(context, intent);
        } catch (Exception error) {
            Log.e("DdlAlarm", "Reminder delivery failed", error);
            ReminderTrace.mark(context, intent.getIntExtra("id", -1), "overlay_error", error.toString());
            delivered = false;
        }
        if (delivered) {
            // A finite service is needed only for sound, an allowed overlay,
            // or direct vibration when notification permission is unavailable.
            String channel = intent.getStringExtra("channelId");
            boolean effects = intent.getBooleanExtra("directVibration", false)
                    || (channel != null && channel.endsWith("_sound"))
                    || (channel != null && channel.startsWith("ddlr_high_") && ReminderBanner.allowed(context));
            if (effects && !AlarmService.dispatch(context, intent)) {
                // The system notification has already been submitted. Preserve
                // the diagnostic if foreground fallback was denied by the OS.
                ReminderTrace.mark(context, intent.getIntExtra("id", -1), "effects_unavailable", "foreground_service_denied");
            }
        }
    }
}
