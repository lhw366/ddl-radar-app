# DDL 雷达

离线优先的 Android 截止日期、梯度提醒与课表应用。Web 页面用 Capacitor 承载；后台提醒由 Android 原生闹钟派发，不依赖页面常驻。

## 从哪里开始

- 使用者：应用内“设置 → 检查更新”；顶部提示发现新版后，可选择立即更新或稍后提醒。覆盖安装保留数据。
- 开发者：[架构](docs/ARCHITECTURE.md)、[贡献流程](CONTRIBUTING.md)、[发布说明](docs/RELEASE.md)。
- 教师与学生：[Git / GitHub 实战教学](docs/git-github-course.md)。

## 目录

```text
www/                         APK 的 Web 源码，以此仓库为准
  js/app.js                  任务、梯度计划、前台补弹、更新入口
  js/nativenotify.js         串行同步到原生；成功之后才确认保存
  js/timetable.js            二维课表解析、核对、去重导入
  data/                      可离线导入的参考课表
android/app/src/main/        原生调度、通知、声音、悬浮横幅、OCR
android/app/src/androidTest/ 真实 Android 运行测试与用户课表样本
tests/                       Web 行为回归、模拟器宿主检查
.github/workflows/android.yml 检查 → 构建 → 主分支发布
docs/                        架构、教学与发布文档
```

## 本地检查

需要 Node.js 20、JDK 17、Android SDK；仅运行 Web 检查不需要 Android SDK。

```sh
npm ci
node --test tests/functional-*.cjs
npx cap sync android
```

Android 完整检查在 GitHub Actions 的一次性 Android 16 模拟器执行。`tests/android_runtime.py` 会清理应用测试数据，强制拒绝在手机上运行。不要把它改成真机清理脚本。

## 发布规则

独立分支先检查，PR 经审查后合入 main。只有 `check` job 全部通过，main 上的 `publish` job 才能写版本元数据和 Release。检查 job 仅有仓库读取权限。应用版本来自 `VERSION`；Android 安装版本号来自原有 workflow 的递增 run number，保持覆盖更新顺序。不要删除重建 workflow 来重置计数。

验证包括完整梯度生成、真实 WebView 保存后后台等待五分钟梯度及截止时间、有限铃声播放开始/完成、横幅截图像素、关闭与服务释放、冷进程广播，以及原图经过实际 ML Kit 后的全部课程核对。声音测试验证播放器状态，不等于测量真机扬声器声压；模拟器不等于 OPPO Find X9s Pro。

## 电池与系统设置

不自动关闭手机省电策略、不常驻轮询、不持有永久 CPU 唤醒锁。每个用户提醒注册单路 AlarmClock，临近提醒时 Android 可能提前退出 Doze；没有真机功耗数据时不承诺零续航影响。声音遵守静音模式、通知音量与勿扰。跨应用自绘横幅需用户授权悬浮窗；系统通知权限独立。

历史包为了兼容旧更新器暂保存在 `apk-latest/`；长期应迁移到独立下载存储并保留旧版升级桥接。安装签名需与已安装版保持一致；签名配置的治理应单独迁移，不能随意更换密钥导致用户无法覆盖升级。
