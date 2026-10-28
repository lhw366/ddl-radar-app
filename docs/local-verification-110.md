# 2.3.8-rc.1 / build 110 验收记录

正式版本使用 GitHub Actions 的 Android 36 Google APIs x86_64 一次性模拟器，
Release APK 为非调试、arm64-v8a、同签名构建。以下结果来自发布 workflow，
真实 OPPO 设备仍须复测。

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

[Android 36 Release workflow](https://github.com/lhw366/ddl-radar-app/actions/runs/40626435239)
的 check 与 publish job 全部成功。git commit
`ad8bc64fc34e432fe171b69c8874fc691127a0fc` 已生成
[v110](https://github.com/lhw366/ddl-radar-app/releases/tag/v110)，公开
`version.json` 返回 code 110，CDN 地址指向 `apk-latest/ddl-radar-110.apk`。
