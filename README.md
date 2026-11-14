# WorkBuddy Token App

手机端凭据助手：**短信验证码登录 → 提取 AT / RT → 本机加密保存多账号 → 一键幂等写入青龙面板**。

| 项 | 值 |
|---|---|
| 平台 | Android 6.0+（minSdk 23 / targetSdk 34） |
| 语言 | 原生 Java 17，**零第三方依赖** |
| 当前版本 | **v1.1.0**（versionCode 2） |
| 安装包 | 见 [Releases](../../releases) —— `app-release.apk` 为直装包 |
| 构建 | GitHub Actions 自动出包，产物同时上传到 Release |

---

## 为什么需要它

WorkBuddy 桌面端把登录凭据加密保存在本地（`$wbEncrypted`，密钥由系统加密层托管、不落盘），**无法离线提取**。
而青龙面板的 `WORKBUDDY_REFRESH_TOKEN` 需要「手机号:AT:RT」每行一个账号。

本 App 在手机上走一次官方插件登录接口（与桌面端同源），用自己的手机号 + 短信验证码换取 AT/RT，
再写入面板，从而绕开"桌面端凭据不可导出"的限制。

---

## 快速上手

1. 从 [Releases](../../releases) 下载 `app-release.apk` 安装（release 包用 debug 签名，可直接覆盖安装）。
2. 打开 App，输入 11 位手机号 → **发送验证码** → 输入短信码 → **登录并获取 Token**。
3. 登录成功后会显示 `AT / RT` 掩码、过期时间；凭据已加密存入本机。
4. 点 **面板设置**，填青龙面板地址（可省略 `http://`，会自动补全）、用户名、密码 → 保存。
5. 点 **推送青龙** → 面板变量 `WORKBUDDY_REFRESH_TOKEN` 按手机号幂等合并。

出问题看点 **② 运行日志** 的「查看 / 导出」，日志含完整 HTTP 链路与异常堆栈，导出内容已脱敏。

---

## 功能总览

详细需求、验收标准、实现位置见 **[docs/REQUIREMENTS.md](docs/REQUIREMENTS.md)**。

| 编号 | 功能 | 状态 |
|---|---|---|
| R-01 | 发送短信验证码 | ✅ |
| R-02 | 验证码登录换取 AT/RT | ✅ |
| R-03 | 可选 RT 试刷校验 | ✅ |
| R-04 | JWT 解析（uid / 过期时间） | ✅ |
| R-05 | 多账号加密存储（Keystore AES-256-GCM） | ✅ |
| R-06 | 复制全部（每行 `手机号:AT:RT`） | ✅ |
| R-07 | 单账号复制 / 分享 / 删除 | ✅ |
| R-08 | 推送青龙面板（按手机号幂等合并） | ✅ |
| R-09 | 面板地址自动补协议与校验 | ✅ v1.1.0 |
| R-10 | 持久化诊断日志（查看/导出/清空） | ✅ v1.1.0 |
| R-11 | 凭据脱敏（日志、UI、导出） | ✅ |
| R-12 | Web 预览版（CORS 反向代理） | ✅ |
| R-13 | 复制单行按钮 | ⛔ 已撤回 |

---

## 目录结构

```
.
├── app/                          # 应用模块
│   └── src/main/
│       ├── java/cn/workbuddy/token/
│       │   ├── MainActivity.java  # 界面与流程编排
│       │   ├── WbApi.java         # WorkBuddy 登录接口封装
│       │   ├── QlPanel.java       # 青龙面板推送（地址规范化 + 幂等合并）
│       │   ├── CredentialStore.java # 加密多账号存储
│       │   └── AppLog.java        # 持久化诊断日志（脱敏/滚动/导出）
│       └── res/                   # 布局与主题（纯 XML，无第三方 UI 库）
├── docs/
│   ├── REQUIREMENTS.md            # ★ 功能需求说明书（迭代对照用）
│   ├── ARCHITECTURE.md            # 技术架构、安全模型、踩坑记录
│   ├── CHANGELOG.md               # 版本历史
│   └── RELEASE.md                 # 发版流程
└── .github/workflows/build-apk.yml # 云构建 + 自动 Release
```

---

## 本地构建

需要 JDK 17 + Android SDK 34（本仓库的开发环境并不具备，因此以 Actions 云构建为准）：

```bash
gradle assembleDebug      # debug 包
gradle assembleRelease    # release 包（复用 debug 签名，便于侧载）
```

也可以直接在 Actions 页面用 **Run workflow** 手动触发，构建完成后自动创建 Release。

---

## 安全说明

- 凭据仅存本机应用私有目录，由 **AndroidKeyStore 生成的 AES-256-GCM 密钥**加密；`allowBackup=false`，不参与云备份。
- 日志、界面、导出内容对手机号 / AT / RT / 密码 / 验证码统一脱敏；`/envs` 的请求与响应体一律不写摘要。
- 面板密码同样加密保存，可随时「清除面板配置」。
- 本工具不含任何后台上报；除 WorkBuddy 官方接口与你自己填写的面板地址外不访问其他域名。

> AT/RT 等同账号密码，请勿截图外发。本项目为个人自用工具，与 WorkBuddy / 青龙面板官方无关。

---

## 已知限制

- 未接入正式签名证书，release 包用 debug 密钥签名（自用侧载足够，不适合上架）。
- Web 预览版仅监听内网，用于桌面浏览器调试，不要映射到公网。
- 短信登录依赖官方接口，若接口变更需同步更新 `WbApi.java`（接口常量集中在文件头部）。
