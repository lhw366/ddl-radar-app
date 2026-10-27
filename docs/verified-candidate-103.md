# build 103：提前投递恢复候选版

此记录仅描述公开软件构建及模拟器验收，不含手机原始诊断。

- 版本：2.3.7-rc.2，build 103，SCHED_VER=17。
- 检查提交：70dd30525722aa6f7a2689ca2235d5a929ece23d。
- [完整 workflow](https://github.com/lhw366/ddl-radar-app/actions/runs/40490526957)：111 项功能检查、33 项原生检查，以及冻结/冷进程宿主检查通过。
- 提前投递恢复 4 项、历史迁移 5 项，保留真实五分钟梯度及截止验收。冻结模拟器入口延迟 34 ms；不代表厂商手机达到同样时效。
- 复用 workflow 原始签名 arm64-v8a Release，非调试，与上一版相同签名，可覆盖安装。
- APK SHA-256：a29efc7656661b831fcd1747068350cd849a76ff2151a3ae64b86dfc3827bd17。
- 文件长度：12486059 字节。

修复详情见 [提前投递恢复](early-alarm-recovery.md)。候选身份保留在名称和说明中，尚未确认解决厂商后台迟到。

[发布页](https://github.com/lhw366/ddl-radar-app/releases/tag/v103)使用当前发布入口，以兼容旧 App 的 latest 查询。公开无需登录的备用 API 和标准 CDN 均已核实返回 103；从首选实际下载地址完整获取的 APK 与上述摘要相同。版本索引和下载文件指向同一份已检查产物。
