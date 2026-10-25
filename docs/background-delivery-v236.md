# v2.3.6：替换到点入口

## b96 真机结论：仍然失败

2026-10-25 的 OPPO PME110 / Android 16 记录（北京时间）：

| 时刻 | 事件 |
| --- | --- |
| 15:42:22.515 | 按 Home 进入后台 |
| 15:43:00.000 | 普通任务预计提醒 |
| 15:45:43.298 | 用户重新进入 App |
| 15:45:43.310 | 广播执行：回前台后 12ms，已迟到 163310ms |

`scheduleVersion=15`、`lastReceiverUrgent=true` 说明 b96 的升级迁移和广播标志均已生效。通知、精确闹钟、悬浮窗均已允许；没有系统省电模式、后台受限或震动 AppOp 拒绝。广播之后约 99ms 完成横幅布局，延迟发生在入口执行之前。不能再把它归因于悬浮窗、震动通道或遗漏广播优先级，也不能宣称 b96 在 OPPO 上修复成功。

仅应用记录仍无法区分 OEM 的 AlarmManager 延后、广播排队和进程/主线程未运行。v2.3.6 去掉已反复失败的广播中转，但不能用模拟器结论承诺特定 ColorOS 行为。

## 新的执行路径

旧：AlarmManager → 广播 → 通知 → 请求短时服务 → 震动与横幅。

新：AlarmManager → `PendingIntent.getForegroundService` → 短时服务立即 `startForeground` → 通知、震动与横幅。

- 每个到点事件仍只有一个系统闹钟，无广播备份、轮询、长驻 CPU 锁或后台保活服务。
- 服务只在提醒到点运行；无原生效果时立即退出，震动短时持有，响铃最多数秒；手动保留的横幅不长期持有 CPU 锁。
- 保留匹配的 `specialUse` 类型声明；旧版本曾有服务类型不匹配问题，不能复制旧服务实现。
- 规格升级 16：先登记服务令牌，再取消 v14 普通和 v15 urgent 广播令牌。开机、覆盖安装、首次对账均迁移。
- 去重保护扩展到服务效果，重复启动不会再次震动；取消任务也取消新的服务闹钟。
- 诊断增加 `alarm_entry` 与 `lastEntryRoute`，明确区分服务入口和旧广播，保留进入后台/回前台时间。不把“已请求震动”当作硬件确认。
- 静音时默认纯震动、可关闭；静音时不播放声音，不改音量、勿扰、省电或白名单。

## 验证要求

运行非调试的签名 Release（模拟器使用 x86_64，手机包使用 arm64-v8a），测试 APK 与目标同签名；无需在产品中添加调试出口。只在一次性模拟器通过 root 读取测试证据。

1. 用真实 v14 / v15 系统广播令牌升级，确认旧令牌取消，新服务入口只投递一次。
2. 重复服务启动不重复效果，取消服务闹钟后不投递。
3. 通过真实页面保存普通任务，结束测试进程，禁用旧 `AlarmReceiver`，重开后按 Home；确认应用已进入缓存冻结，再等普通截止，不重新打开 App。
4. 检查系统实际服务入口、无广播记录、桌面仍在前台、静音下新增完成的震动、横幅布局、唤醒锁释放与服务退出。
5. 保留正常五分钟梯度和截止、测试提醒、进程终止、灭屏、无悬浮窗、拒绝通知、静音选择与勿扰的回归。

这些检查验证架构、迁移和 Android 基线；OPPO 验收必须以手机停留桌面时收到实际提醒为准。

## 官方依据

- [PendingIntent.getForegroundService](https://developer.android.com/reference/android/app/PendingIntent#getForegroundService(android.content.Context,int,android.content.Intent,int))：系统可直接启动前台服务。
- [后台启动前台服务的例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：用户请求的精确闹钟是允许的启动场景。
- [AOSP DeskClock 调度代码](https://android.googlesource.com/platform/packages/apps/DeskClock/+/refs/heads/main/src/com/android/deskclock/alarms/AlarmStateManager.kt)：到点操作以服务 PendingIntent 调度。现代 API 使用前台服务并及时晋升，不能直接复制旧 getService 写法。

## 手机验收

覆盖安装并打开一次，确认版本。设置一个约六分钟后截止的普通任务，开启提前五分钟与截止提醒，系统保持静音。按 Home 后等待两条提醒，不重新打开。只有这两条在桌面按时出现，才记为该设备验证通过。若仍失败，新诊断可判断服务入口是否同样被系统延后；不能再次用“模拟器通过”覆盖该失败。
