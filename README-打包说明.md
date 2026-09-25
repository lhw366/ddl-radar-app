# DDL雷达 · APK 云打包说明

本目录是完整的 Capacitor 安卓工程：`www/` 是网页版应用，`android/` 是原生壳工程（图标/启动图已注入），`.github/workflows/android.yml` 会在 GitHub 云端自动编译出 APK。

## 打包步骤（全程无需安装 Android Studio）

1. 在 GitHub 上新建一个仓库（New repository，名字随意如 `ddl-radar`，Public 免费构建无限量，Private 也有每月 2000 分钟免费额度够用）
2. 把本目录推上去（在本目录打开命令行）：

   ```bash
   git init
   git add .
   git commit -m "DDL雷达 v1"
   git remote add origin https://github.com/<你的用户名>/ddl-radar.git
   git push -u origin main
   ```

   （如果本地 git 没配置过身份，先执行 `git config --global user.name "你的名字"` 和 `git config --global user.email "你的邮箱"`）

3. 打开仓库页面 → **Actions** 标签 → 等「Build Android APK」跑完（首次约 3-5 分钟）
4. 点进该次运行 → 底部 **Artifacts** → 下载 **DDL雷达-apk** → 解压得到 `app-debug.apk`
5. 传到手机安装（ColorOS 首次安装外部 APK 需允许「安装未知来源应用」）

## 以后更新应用

改 `www/` 里的网页文件（或直接改 `ddl-app/` 后复制过来），然后：

```bash
npx cap sync android
git add . && git commit -m "update" && git push
```

Actions 会自动重新出包。

## 注意

- 当前 APK 是 WebView 壳：应用内提醒（横幅/声音/震动）可用；系统级精确闹钟（锁屏必达）是下一步的原生功能，见设计文档 §5
- 首次启动如提示网络权限，与节假日在线更新有关；离线时内置 2025/2026 数据照常工作
