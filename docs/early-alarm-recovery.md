# v17 提前投递与旧令牌恢复

本页描述 v17。v18 在此基础上加入[有限的临近提醒保护](near-reminder-protection.md)，提前到达后三分钟内可等待真实到点；不再立即结束服务。等待仍不保持 CPU 唤醒。

本说明只描述通用软件行为和合成测试，不包含用户诊断、设备型号或复现时间。

## 问题

提前超过两秒进入服务的提醒原先会直接返回 false。若这次调用已经消耗了系统一次性闹钟，本地列表仍显示 scheduled=true，却没有任何机制在真正到点重新执行。后台延迟与此问题相互独立：补上提前恢复，不足以宣称后台投递延迟已解决。

旧令牌的 URI 只包括事件 ID，修改到点时间会通过 FLAG_UPDATE_CURRENT 替换同一令牌的 extras；尚未执行的旧系统请求可能带上更新后的时间。取消也曾使用带空参数的更新查询。现改为每次发生的 ID+到点时间参与 URI、完整 ID 参与 requestCode，取消使用 FLAG_NO_CREATE 并取消令牌本身。

## 行为

- 排入新的一次提醒，持久化 registered_<id>=at，然后注销旧发生和历史令牌。SCHED_VER=17 使旧排期在更新/协调时迁移。
- 派发须匹配当前登记的发生，避免已取消或被新到点时间取代的在途请求重新响铃。
- 提前超过两秒到达且与当前持久化事项、登记匹配时，用同一个令牌替换为 ELAPSED_REALTIME_WAKEUP 的 setExactAndAllowWhileIdle，期限按剩余时长换算。不是新增并行闹钟。
- 每个发生只恢复一次。系统再次提前调用或拒绝恢复时，记录 exhausted/failed 并在诊断明确显示失败，避免无限重试；不将失败包装为成功。
- 提前到达不记为已派发，不产生通知/震动/声音/横幅，不保留服务等待。正常到点继续使用既有去重、静音规则、短时效果和退出机制。
- 此处恢复不保证消除系统投递延迟。exact-idle 仍受系统频率策略影响；无需修改系统设置，也不引入常驻服务或轮询。

## 验证

EarlyDeliveryTest 从真实 PendingIntent 启动服务注入提前到达，验证恢复后真正到点只通知一次。其他用例覆盖重复提前、修改后的旧令牌/在途参数和取消事项。AlarmPriorityMigrationTest 覆盖广播版本及原服务版本迁移。既有普通五分钟梯度和截止的 Release 运行检查继续保留。

诊断把 lastEntryExpectedAt/lastEntryDelayMs 与 lastExpectedAt/lastDelayMs 分开：入口可能属于另一个被提前拒绝的事项，不能把两个事件拼接为同一条证据。旧显式测试的背景观测也不能用于解释不同的普通任务。

[PendingIntent 的身份与更新语义](https://developer.android.com/reference/android/app/PendingIntent) · [系统精确闹钟及其限制](https://developer.android.com/develop/background-work/services/alarms)
