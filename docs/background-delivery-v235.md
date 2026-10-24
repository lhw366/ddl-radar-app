# v2.3.5 后台投递调查

设备：OPPO PME110 / Android 16；用户确认按 Home 回桌面，没有划掉任务。b90 记录里通知、悬浮窗、精确闹钟、全屏权限均允许；系统静音、通知音量 0、DND ALL。不能再以缺悬浮窗权限解释。

最后一次预计 2026-10-25 00:06:48.093（北京时间），receiver 在 00:06:56.368 才运行，迟到 8275ms；随后 2ms 开始 delivery、10ms 提交通知、38ms 进入服务、41ms 请求纯震动、60ms 窗口完成布局。接收时 appVisible=true，不能证明后台成功。上一条提醒在前台 receiver 仅迟到 40ms。vibration=false 是 quiet 通道防止重复震动，原生 ALARM 触觉单独请求。

## 已确认的代码问题

1. PendingIntent 广播没有 FLAG_RECEIVER_FOREGROUND。AlarmManagerService 启动闹钟并不替调用方自动补此标志；官方 Intent 文档指出该标志允许接收者以前台优先级运行。
2. 只追加 flag 然后 UPDATE_CURRENT 不会改变旧令牌的 Intent flags。PendingIntentController 只 replaceExtras。改用独立 urgent 身份，注册成功后取消旧广播，规格升级到 15。保留单路闹钟和交付去重，兼容 BootReceiver 覆盖安装恢复。
3. getNextAlarmClock 返回设备级最近闹钟，不能作为本应用队列的系统确认。b90 的 sysNextAt 与 nextAt 不一致不必然是漏排；增加 showIntent creator 来源。
4. 在 instrumentation 内按 Home，测试线程会使目标进程保持活跃。新增 host 测试结束 instrumentation 后重新打开一次，再 Home，通过 am freeze（非 sticky）冻结并检查 dumpsys。只有冻结已确认、正常截止在同一 PID 中被唤醒且 App 始终未恢复前台才通过。

## 证据边界

前台优先级是可确认的遗漏，但不是 ColorOS 根因已被证实。冻结、系统排队和主线程阻塞需要 OPPO 的 adb logcat/dumpsys 才能再细分。新增只读生命周期时间与接收实际标志帮助下一次现场诊断，不把 vibration_requested 或 overlay_visible 当作硬件/屏幕确认。

## 官方参考

- [Intent.FLAG_RECEIVER_FOREGROUND](https://developer.android.com/reference/android/content/Intent#FLAG_RECEIVER_FOREGROUND)
- [Android 16 AlarmManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java)
- [Android 16 PendingIntentController](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/PendingIntentController.java)
- [缓存应用冻结器](https://source.android.com/docs/core/perf/cached-apps-freezer)

不修改省电、音量、勿扰或冻结设置；冻结命令仅在隔离模拟器测试中使用。到点服务和短时唤醒锁的释放策略保持不变；不引入重复兜底闹钟或后台轮询。
