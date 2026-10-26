# ColorOS：后台投递定位

## 待定位的行为

需要区分的一类故障是：权限显示已授权，但后台提醒迟迟未执行，直到返回前台才开始执行服务。此文仅描述软件行为，不包含手机原始诊断、设备型号或复现时间。

系统静音下的 quiet 通道不负责震动是设计行为，不能拿此通道的 `vibration=false` 解释入口此前迟到。

仅应用服务日志不能区分系统延后投递、整个进程冻结、主线程阻塞和休眠。`isBackgroundRestricted()` 仅代表 Android 公开状态，不能证明 ColorOS 自启动已放行。设备最近闹钟也不是本应用全部闹钟的系统排定回执。

## GitHub 核查

- [BlackyHawky/Clock 的 AlarmStateManager](https://github.com/BlackyHawky/Clock/blob/main/app/src/main/java/com/best/deskclock/alarms/AlarmStateManager.java)：使用原生 AlarmManager 与服务 PendingIntent，并没有普通应用可以静默解除 ColorOS 限制的实现。其 Find X9 Pro 问题 #467 是启动列表短暂卡顿，不能冒称同一后台故障的解决案例。
- [AutoStarter](https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt)（MIT）：提供 ColorOS 自启动页面的候选组件。页面可打开不等于已授权，无法通过这些跳转读取或自动批准开关。
- [NotifyKit 的 OEM 说明](https://github.com/marcocrupi/react-native-notify-kit#oem-background-restrictions)：记录 OEM 对调度和前台服务的额外限制，推荐用户检查厂商入口。引用不代表该库已在此 OPPO 上验证。
- [android-background-work skill](https://github.com/GuillemRoca/agent-skills-android/blob/68fd141eec5ad2029ae8393e510dbe46dea48c36/skills/android-background-work/SKILL.md)：审阅其原生精确提醒、受限后台和可观测性检查步骤。它不是 ColorOS 权限绕过工具；关于通知权限与前台服务等个别表述仍以 Android 官方文档为准。
- Root/LSPosed 模块属于系统修改，不能作为普通 APK 的可复用修复方式。

## v2.3.7-rc.1 候选改进

1. 诊断顶部直接显示回前台才送达的失败证据。ColorOS 自启动状态显示“未知”，不伪造授权成功。
2. 用户点击后尝试已知的自启动页面；不可用或受保护时退到本应用详情。只打开页面，不修改设置，不打开省电豁免申请。
3. 仅点击 5s / 60s 测试时，创建一个原生定时观测，记录独立工作线程和主队列分别何时运行、主线程调用栈以及两种单调时钟。普通梯度、课程和截止不创建探针。
4. 观测不唤醒设备、不持有唤醒锁、不启动服务、不派发提示。单次执行后工作线程退出。设备若进入深睡，观测可以延迟，诊断需明确该限制。
5. 记录 `onStop` 完成时刻，排查退后台过程中是否发生主线程阻塞。

这是一份定位候选版。它改善证据和授权入口，不构成“OPPO 提醒已修复”的结论。保留原有静音震动/静音禁声偏好，不增加常驻守护或后台轮询。

## 手机侧最小确认

先仅查看 DDL雷达的“自启动 / 自动启动 / 关联启动”状态，保留当前电池优化。随后选择 60s 后台测试并按 Home，保持桌面和屏幕亮着等待，结束后复制提醒诊断。新字段能区分：工作线程及时但主队列延后、进程可运行但服务入口延后，以及工作线程同样延后。是否系统冻结仍需结合这些证据，不能仅因测试失败就下结论。

普通梯度最终仍须用真实任务在桌面验收；测试探针与提醒路径分离，仅用于定位，不充当提醒兜底。
