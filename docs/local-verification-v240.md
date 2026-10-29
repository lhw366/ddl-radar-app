# v2.4.0-rc.1 / build 121 验收记录

发布日期：2026-10-29。账户功能 PR：[#13](https://github.com/lhw366/ddl-radar-app/pull/13)。
最终检查和发布：[workflow 121](https://github.com/lhw366/ddl-radar-app/actions/runs/40853127378)，check 与 publish 均成功。

- CI 通过 158 项 Web 功能检查、7 项 PostgreSQL/官方 Auth SDK 检查、54 项 Android 36 instrumentation，以及冻结、冷启动等宿主断言。
- 新增 Android 用例确认 Release 桥接、AES-GCM 密文/随机 IV、篡改及路径穿越拒绝、凭据不进入网页存储、邮箱回跳解析、账户表单及四主题布局。
- 初轮截图出现旧帧，已修正为等待 WebView visual-state callback 后采样；最终 workflow 包含该修正。没有将旧帧视为深色主题视觉验收成功。
- 普通 2h/1h/30min/5min、自定义、截止与课程提醒继续走已有原生入口；核心调度器、短时保护、静音策略及电池设置没有改动。
- Supabase 项目 URL 与 APK 配置匹配；邮箱注册开启、验证要求开启。实际匿名读表和保存接口返回 401/42501。
- Supabase 授权接入后，在实际数据库中用临时身份验证本人读写、跨用户隔离、直接写表拒绝、过期版本拒绝，全部回滚；没有发送邮件或保留测试账号。
- 已登记初始迁移并部署内部 RLS 函数权限收紧；对应新增本地数据库检查通过。此后只有数据库权限与文档变更，APK 内容保持 build 121。

实际下载入口：
[ddl-radar-121.apk](https://gcore.jsdelivr.net/gh/lhw366/ddl-radar-app@main/apk-latest/ddl-radar-121.apk)。
文件大小 12,583,219 字节；下载文件与 GitHub Release digest 一致。

```text
APK SHA256:
7b2504771a66efe79f256f13fe6e94c79786fe2b240bf5b65dc7df9d8caa0906
签名证书 SHA256，与 build 116 一致:
14c9718396b61071edc14e0268f18184457d2c9a0ae07630205cbe824574bf10
```

APK 内置版本为 2.4.0-rc.1 / 121，内置 Supabase 项目为 czvlplupoqzxuqycoavt。
更新元数据与 GitHub Release 均指向 121，cdn.jsdelivr.net 元数据已刷新确认。

真实项目成员邮箱的邮件投递、手机注册/登录、找回密码和第二台设备同步仍需用户验收。
默认 SMTP 仅先用于项目成员测试，面向学生开放前需配置自己的邮件服务。
以上模拟器检查不代表 OPPO 实机提醒时效或手机网络连通性已验收。
