# 2.3.8-rc.1 本地验收记录

候选版本使用 Android 36 Google APIs x86_64 一次性模拟器，Release APK 为非调试、
同签名构建。以下结果来自当前提交，真实 OPPO 设备仍须复测。

- 功能检查：113/113 通过。
- Release、Android 测试 APK 与 JVM 单元测试编译通过。
- `EarlyDeliveryTest`：4/4 通过，确认提前回调重排到 AlarmClock 补偿路径，第二次
  提前回调不会创建重试风暴，编辑与取消不会投递旧令牌。
- `NearReminderProtectionTest`：4/4 通过，确认普通提醒按 Home 后由有限保护完成，
  等待不持 CPU 锁，远期事项不常驻，派发可交给下一条临近截止。
- `ReminderRuntimeTest#realSavedGradientUsesBackgroundOverlayAndDeadlineKeepsNotificationFallback`：
  真实等待 328.6 秒通过，覆盖完整五分钟梯度、截止、声音与震动策略、通知兜底、
  横幅自动关闭和服务退出。
- `SilentReminderRuntimeTest#fiveSecondBridgeTestUsesSilentAlarmHapticsWithoutAnyChime`：
  通过，5 秒链路保持准确，静音时只使用触觉。
- `ReminderBannerThemeTest`：2/2 通过；四季月份映射和当前季节横幅在桌面真实绘制
  均通过，截图见 `output/seasonal-banner.png`。

当前环境无法解析 GitHub 域名，因此该文件不宣称 GitHub Actions 或公开 CDN 已更新。
接入手机构建和更新入口前，仍须把当前提交推送并观察对应 Actions。
