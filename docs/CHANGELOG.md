# 版本历史

遵循语义化版本：`主版本.次版本.修订号`（对应 `versionName`），`versionCode` 单调递增。

---

## 文档修订 — 2026-11-15（无代码变更，版本仍为 v1.1.0）

### 变更

- **仓库转为公开**：便于他人/换机时直接在 Releases 下载 `app-release.apk`，不再需要仓库权限。
  仓库内容仅含源码、文档与构建工作流，**不含任何账号、令牌或面板地址**。
- `REQUIREMENTS.md` 新增 **§1.4 来源与定位**：明确本项目**依据
  [L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily) 打包自用**，
  仅为该脚本提供 `WORKBUDDY_REFRESH_TOKEN`（每行 `手机号:AT:RT`），不修改也不内置上游脚本；
  并写明数据流边界与两处格式适配点（R-06 / R-08）。
- `README.md` 新增「项目来源与定位」章节；修正功能总览中撤回需求的编号（`R-13` → `X-01`，与需求书一致）。

---

## v1.1.0 — 2026-11-15

`versionCode 2`

### 新增

- **持久化诊断日志**（R-10）：`AppLog` 按天写应用私有目录，7 天滚动、单文件 512KB 分片，
  异常记录类名 / message / 完整堆栈 / cause 链，同时输出 logcat；UI 新增「查看 / 导出 / 清空」，
  清空区分「仅屏幕」与「全部」。
- **面板推送全链路诊断**：记录每步 HTTP 方法、URL、状态码、耗时与响应摘要（`/envs` 除外），
  网络 / 协议 / 超时异常分类；29 处 catch 分支全部留痕。
- **Web 端同等能力**：`web/diag.js` 供浏览器与 Node 共用（脱敏 + 地址规范化），
  页面日志持久化 400 条并可导出，代理写 `web/logs/diag-*.log`。
- 面板配置保存时回显规范化后的最终地址。

### 修复

- **面板推送失败 `no protocol: 192.168.124.11:5700/api/auth/login`**（R-09）：
  地址省略 `http://` 时 `new URL()` 抛异常。现自动补协议并校验协议 / 主机名 / 端口范围，
  剥离 query 与 fragment，拒绝 URL 内嵌账号密码；Android 与 Web 两端同规则。
- 畸形输入如 `http://` 曾被误解析为 `http://api/api`，现已判为不合法。

### 变更

- `versionName` 1.0.0 → 1.1.0，`versionCode` 1 → 2。

---

## v1.0.0 — 2026-11-14

`versionCode 1` — 首个可安装版本

### 新增

- 短信验证码登录（发码 / 登录 / 可选 RT 试刷校验）。
- AT/RT 提取、JWT 解析（`sub` / `preferred_username` / `exp`）。
- 多账号加密存储：AndroidKeyStore + AES-256-GCM，`allowBackup=false`。
- 「复制全部」输出多行 `手机号:AT:RT`；列表内单账号复制 / 分享 / 删除。
- 推送青龙面板 `WORKBUDDY_REFRESH_TOKEN`：按手机号幂等合并，`PUT`/`POST` 自动选择，回读校验。
- Web 预览版：`web/server.js` 本地反向代理绕过 CORS，`web/index.html` 页面。
- GitHub Actions 云构建（debug + release，release 复用 debug 签名便于侧载）。
- 「复制单行 · 手机号:AT:RT」按钮（**已于当日撤回，见下**）。

### 撤回

- **「复制单行 · 手机号:AT:RT」按钮**：实机测试效果较差，按用户要求撤回。
  移除按钮、多账号选择弹窗、`lastPhone` 排序与 `wb_last_phone_v1` 持久化。
  注意 `手机号:AT:RT` 这一 env 行格式仍在使用（复制全部 / 单账号复制 / 推送面板），未改动。
  详见 `REQUIREMENTS.md` 的 `X-01`。

---

## 待开发（Backlog）

| 编号 | 内容 | 优先级 |
|---|---|---|
| — | 正式签名证书（如需分发到他人设备） | 低 |
| — | 日志按需上传（当前仅本地导出） | 低 |
| — | 面板变量多账号批量开关（例如临时禁用某账号） | 低 |
