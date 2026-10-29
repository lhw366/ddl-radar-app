# DDL雷达账户与云端接入

账户版本：2.4.0-rc.1。项目：`czvlplupoqzxuqycoavt`。
Supabase 管理账户用于开发；App 中的邮箱账户属于应用用户，两者独立。
连接 GitHub 只负责代码和数据库变更的部署，不会自动建立 App 登录。

## 已实现的应用流程

- 邮箱注册、邮箱验证、重发验证邮件、密码登录、找回密码与设置新密码。
- 使用 Supabase 官方客户端和 PKCE；验证和找回邮件应在发起请求的同一台设备打开。
- Android 会话和 PKCE 验证凭据通过 AndroidKeyStore AES-256-GCM 加密，存入不参与设备备份的目录。浏览器会话只保存在当前网站的本机存储。
- 登录不会自动上传；用户明确选择后同步事项、课表和昵称。照片、提醒偏好、运行诊断、反馈草稿和本机测试角色不进入云端快照。
- 游客工作区和各账户的本机工作区独立。退出后回到游客工作区；重新登录可恢复该账户尚未同步的本机工作区。
- 本机优先：保存后立即按原有路径排定系统提醒，云端不参与到点派发。离线可以继续编辑；恢复网络或回到前台时再同步。不增加后台轮询、长期服务、CPU 唤醒锁或电池白名单。
- 每次写入检查服务器版本。两台设备同时修改时暂停同步，用户选择本机或云端；不自动合并课表，避免引入时段重叠。
- 替换前留一份本机副本，可在账户卡片中恢复；云端保留最近 5 次历史快照。历史用于撤回误覆盖，不代替独立灾难备份。

## 首次配置

1. 在 Supabase 项目的 SQL Editor 执行一次 `supabase/migrations/202610290001_accounts.sql`。请完整执行；SQL Editor 手工运行建议放在 `BEGIN; ... COMMIT;` 中，任一失败则整批回滚。
2. 在 Authentication → URL Configuration → Redirect URLs 添加 **`ddlradar://auth/callback`**。保持精确路径。浏览器版本如果要公开部署，另添加实际 HTTPS 网站地址，不要用任意域名通配符。
3. Authentication → Providers → Email：启用 Email，保留 Confirm email。密码长度下限建议设为 10；App 同样检查下限。
4. 向学生公开前配置自有 SMTP。Supabase 默认邮件服务仅面向项目团队成员地址，额度低，不适合真实用户注册。不要为绕开邮件问题关闭验证。
5. 公开连接配置位于 `config/cloud.public.json`。包含 Project URL 和 `sb_publishable_...`；这些值设计为可出现在 App 中。`sb_secret_...`、数据库密码、service_role 和管理 API token 都不得放入 App、公开仓库或聊天。

2026-10-29 对实际项目的只读检查：Auth settings 返回 200；Email enabled；signup enabled；email confirmation required。初始化后匿名读表返回 401 / 42501 permission denied，符合设计。尚未使用真实用户发邮件、登录或在实际项目写入个人数据；这些须在安装候选包后验收。

## 构建与更新

```powershell
npm ci
npm run cloud:build
node --test tests/functional-*.cjs
npm run test:cloud-db
npx cap sync android
```

Node 使用 22 或更新版本。官方客户端随 APK 打包，启动不依赖远程 JavaScript CDN。
构建默认读取已核查的公开配置；GitHub repository variables 的 `DDL_SUPABASE_URL` 和 `DDL_SUPABASE_PUBLISHABLE_KEY` 可同时覆盖，以区分测试项目与生产项目。只设置其中一个时构建失败，避免接错项目。

数据库新增变更放进新的 `supabase/migrations/时间戳_名称.sql`，先运行权限回归后部署；不要修改已部署迁移，避免重跑破坏现有数据。当前协议 schema=1，版本不兼容时 App 拒绝覆盖本机数据。

## 数据库权限

`ddlr_cloud_state` 每个 Auth 用户一行，`ddlr_cloud_history` 保存该用户历史。
两表启用 RLS，仅 authenticated 的 `auth.uid() = user_id` 可以读取。
anon 没有权限；authenticated 没有直接 INSERT/UPDATE/DELETE 权限。
写入只经过 `ddlr_save_state(expected_revision,new_payload)`：函数从经过 Supabase 验证的 JWT 取 UID，不接收客户端用户 ID，不读取本机 profile.role。
函数固定空 search_path，锁定该用户记录、核对 revision、保存历史，并在同一事务中更新。
Supabase Auth 用户删除时，外键会清除对应当前和历史云端数据。App 当前没有自助注销账户按钮；如需删除账户，需管理员在 Supabase Auth 中执行，不应将管理密钥交给手机。

## 验收步骤

1. 使用项目团队成员邮箱在候选包注册，验证邮件在同一台手机打开，验证后登录。学生邮箱需要自有 SMTP。
2. 选择「用本机数据开启同步」，确认事项、课表、昵称进入该用户的云端记录；照片和诊断不出现。
3. 第二设备登录后选择「使用云端数据」，确认事项和单双周课表。手机提醒偏好保持各自设置。
4. 设备 A 离线修改，设备 B 修改并同步，再让 A 回到前台：出现冲突选择；两边内容不被静默覆盖。
5. 退出账号后恢复游客工作区；重新登录，使用「恢复此账户的本机工作区」找回尚未同步的修改。
6. 同步暂停、断网、退出登录时，已有普通梯度与截止提醒依旧由原生系统排期派发；不能只测试 5 秒链路。
7. 申请找回密码，在原设备打开邮件，设置新密码；旧密码登录失败，新密码成功。

## 官方依据

- [Auth 密码与验证](https://supabase.com/docs/guides/auth/passwords)
- [PKCE](https://supabase.com/docs/guides/auth/sessions/pkce-flow)
- [移动端回跳](https://supabase.com/docs/guides/auth/native-mobile-deep-linking)
- [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [公开密钥和服务器密钥](https://supabase.com/docs/guides/getting-started/api-keys)
- [SMTP 配置和默认邮件限制](https://supabase.com/docs/guides/auth/auth-smtp)
- [Android 加密](https://developer.android.com/privacy-and-security/cryptography)
