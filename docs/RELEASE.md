# 发版流程

本仓库的 APK **只通过 GitHub Actions 构建**，构建成功后自动创建 Release 并挂载安装包，
因此即使本地安装包丢失，也能随时从 Releases 页面重新下载。

## 1. 改版本号

`app/build.gradle`：

```gradle
versionCode 3          // 每次发版 +1，不能回退也不能重复
versionName '1.2.0'    // 语义化版本
```

同时更新 `docs/CHANGELOG.md` 增加对应小节。

## 2. 触发构建

两种方式，任选：

- **推送到 main**：修改 `app/**` 或构建脚本后自动触发（`.github/workflows/build-apk.yml` 的 `push` 规则）。
- **手动触发**：Actions → `Build Android APK` → `Run workflow`，可填 `version`（例如 `v1.2.0`）。
  不填则默认取 `app/build.gradle` 中的版本号生成 `v<versionName>`。

## 3. 构建产物

| 产物 | 说明 | 保留期 |
|---|---|---|
| Release 附件 `app-release.apk` | **推荐安装**，debug 密钥签名可直接覆盖安装 | 永久 |
| Release 附件 `app-debug.apk` | 便于排查（含调试信息） | 永久 |
| Actions Artifact | 构建临时产物 | 30 天，过期不影响 Release |

安装包命名约定：`WorkBuddyToken-v<versionName>-release.apk` / `-debug.apk`。

## 4. 下载与安装

- 手机直接打开仓库 **Releases** 页面 → 展开最新版本 → 下载 `app-release.apk`。
- 私有仓库需先登录 GitHub；如需免登录直链，可把仓库改为公开（Settings → Danger Zone → Change visibility）。
- 安装时如提示"未知来源"，在系统设置里允许对应浏览器/文件管理器安装即可。
- 升级覆盖安装不需要卸载：release 包每次都用同一个 debug 签名，签名一致即可覆盖。

## 5. 回滚

Release 是按 tag 隔离的，旧版本附件一直可下载。
需要回退时直接下载旧 tag 的 `app-release.apk` 覆盖安装即可（版本号更低的包允许覆盖安装，
若系统拒绝，先卸载再装，注意卸载会清空本机账号凭据）。

## 6. 构建失败排查

1. 看 Actions → 失败 job 的日志，重点看 `Android SDK packages` 与 `gradle assemble*` 两步。
2. 常见原因：
   - `Failed to find package 'tools'` —— 不要引入 `android-actions/setup-android@v3`；
   - Gradle / AGP 版本不匹配 —— 用 `gradle/actions/setup-gradle@v4` 显式指定版本；
   - `Git Repository is empty` —— 空仓库需先用 Contents API 造一个初始 commit。
3. 构建脚本与 SDK 安装逻辑都集中在 `.github/workflows/build-apk.yml`，改动前先读文件内注释。
