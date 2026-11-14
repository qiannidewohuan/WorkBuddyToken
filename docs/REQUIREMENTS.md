# 功能需求说明书

> 本文件是**需求的唯一权威来源**。任何新增 / 修改 / 撤回需求，都必须在这里追加或更新条目，
> 再动代码；后续迭代先读这里，不需要回溯聊天记录。
>
> - 适用版本：**v1.1.0**（versionCode 2）
> - 上游依据：**[L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)** —— 本项目为它「打包自用」，详见 §1.4
> - 状态标记：`✅ 已实现` / `⛔ 已撤回` / `🚧 待开发`
> - 编号规则：`R-数字` 功能需求，`N-数字` 非功能需求，`X-数字` 已撤回/废弃需求

---

## 1. 背景与目标

### 1.1 背景

- WorkBuddy 桌面端登录后签发 `AT`（Access Token）与 `RT`（Refresh Token），凭据以 `$wbEncrypted`
  形式落盘，密钥由 Electron 系统加密层托管（`AtRestCrypto` / `atRestSecretKey`），**离线不可解**。
- 青龙面板的运行脚本需要环境变量 `WORKBUDDY_REFRESH_TOKEN`，格式为
  `手机号:AT:RT`，**一行一个账号**，多账号用换行分隔。
- 因此需要一条「手机端自行登录 → 取得 AT/RT → 写入面板」的通道。

### 1.2 目标

1. 在 Android 手机上通过**短信验证码登录**，换取 AT/RT，不依赖桌面端。
2. 凭据**加密存本机**，支持多账号，可随时复制 / 分享 / 删除。
3. 一键**幂等**写入青龙面板环境变量（不误删其他账号）。
4. 出问题能**自查**：有持久化、脱敏、带堆栈的诊断日志。

### 1.3 非目标

- 不做后台常驻、不做定时自动刷新、不做云端同步。
- 不做通用面板管理工具，只维护 `WORKBUDDY_REFRESH_TOKEN` 这一个变量。
- 不做上架分发（无正式签名证书）。

### 1.4 来源与定位（依据上游打包自用）

- 本项目**依据 [L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily) 打包自用**。
  上游是 WorkBuddy 成长中心的全能签到脚本（青龙面板 / GitHub Actions / 本地运行），
  其运行前提是环境变量 `WORKBUDDY_REFRESH_TOKEN`，格式为每行一个 `手机号:AT:RT`。
- 上游解决"拿到令牌之后做什么"（签到、成长任务、小程序链式任务、积分回读、通知推送、RT 永续续期），
  **不解决"令牌怎么来"**——桌面端凭据加密落盘、离线不可解。本项目就是补这一环的配套工具。
- 数据流边界（务必保持）：

  ```
  本 App：短信登录 → 取 AT/RT → 本机加密保存 → 按上游格式写入面板 WORKBUDDY_REFRESH_TOKEN
  上游脚本：读取该变量 → 轮换续期 → 执行签到/任务 → 回写令牌库（wb_refresh_tokens.json）
  ```

- 与上游的关系约定：
  1. **不修改、不内置、不绑定上游脚本**：无 submodule、无自动同步、不复制上游代码；
  2. 不承担上游功能（签到 / 任务 / 积分 / 推送 / 桌面换血等），这些以上游文档为准；
  3. 上游的 `WORKBUDDY_REFRESH_TOKEN` 格式若调整，本项目只改 **R-06（env 行格式）** 与
     **R-08（面板合并写回）** 两处适配点；
  4. 本项目为个人自用，不向上游提交本项目相关 Issue / PR，也不对外分发。
- 取得方式：直接下载本仓库 Releases 的 `app-release.apk` 侧载安装；上游脚本请从上游仓库获取。

---

## 2. 功能需求

### A. 登录与取凭据

#### R-01 发送短信验证码 ✅

- 输入 11 位手机号，点击「发送验证码」。
- 调用 `POST https://www.workbuddy.cn/v2/plugin/login/send-sms`，请求体 `{"phone": "<手机号>"}`。
- 成功后提示验证码有效期（取响应 `data.expires_in`，缺省 300 秒）。
- **前端前置校验**：手机号不匹配 `1[3-9]\d{9}` 时不发请求，直接提示。
- 失败时给出可操作提示（频控 / 网络异常 / 接口报错原文）。

**验收**：真实手机号能收到短信；非法手机号不产生网络请求；断网时提示网络异常且日志有异常类型与堆栈。

**实现**：`MainActivity#doSendSms` → `WbApi#sendSms`

---

#### R-02 验证码登录换取 AT/RT ✅

- 调用 `POST https://www.workbuddy.cn/v2/plugin/login/token`，
  请求体 `{"login_method":"phone","phone":"<手机号>","sms_code":"<验证码>"}`。
- 从响应 `data` 取 `accessToken` / `refreshToken`（同时兼容下划线命名 `access_token` / `refresh_token`）。
- 两者任一为空时报「响应缺少 accessToken / refreshToken」，不写入存储。
- 业务码 `code != 0` 时直接展示服务端 `msg`，并按关键词追加操作建议。
- 连接层异常（代理 / DNS / 瞬时断网）自动重试 **1 次**，避免把网络抖动误判为验证码错误。

**验收**：正确验证码登录成功并落库；错误验证码提示明确；`code != 0` 不重试；网络失败只重试一次。

**实现**：`MainActivity#doLogin` → `WbApi#login`

---

#### R-03 可选 RT 试刷校验 ✅

- 登录成功后，可选「用 RT 试刷一次验证凭据可用」。
- 调用 `POST https://copilot.tencent.com/v2/plugin/auth/token/refresh`，
  以 `Authorization: Bearer <RT>` 发起，成功后**用返回的新 AT/RT 覆盖**登录得到的值。
- 若返回新 `accessToken` 为空则标记「（未验证）」，不阻断保存。
- **注意**：RT 刷新会轮转凭据，刷新后旧 RT 失效，必须以最新值落库并推送。

**验收**：勾选后能看到验证结果；刷新的新值确实覆盖旧值；刷新失败不影响已获取的登录凭据。

**实现**：`MainActivity#doLogin`（`verify` 分支）→ `WbApi#refresh`

---

#### R-04 JWT 解析（uid / 过期时间）✅

- 解析 AT 的 payload，取 `sub` / `preferred_username` / `exp`。
- UI 展示过期时间的绝对时刻与相对剩余时间。
- 解析失败不阻断流程（容错为空）。

**验收**：展示的过期时间与 token 内 `exp` 一致；畸形 token 只降级不崩溃。

**实现**：`WbApi#claims`、`WbApi#expiryText` / `remainingText`

---

### B. 凭据存储与导出

#### R-05 多账号加密存储 ✅

- 存储介质：应用私有目录 JSON vault，内容整体经 **AES-256-GCM** 加密。
- 密钥：由 **AndroidKeyStore** 生成并保管（非导出密钥），App 内不出现明文密钥。
- 账号字段：`phone / at / rt / user / sub / loginAt / exp`。
- **同一手机号重复登录 = 覆盖**，不新增重复条目；不同手机号追加。
- `allowBackup=false`，不参与系统云备份与换机迁移。

**验收**：重新打开 App 账号仍在；adb 拉取 vault 文件无法直接读出 AT/RT；vault 损坏时降级为空列表不崩溃。

**实现**：`CredentialStore`

---

#### R-06 复制全部 ✅

- 「复制全部」把全部账号按 env 行格式输出，换行分隔：

  ```
  155xxxxxxxx:eyJhbGciOi...:eyJhbGciOi...
  156xxxxxxxx:eyJhbGciOi...:eyJhbGciOi...
  ```

- 该格式**可直接粘贴**为面板 `WORKBUDDY_REFRESH_TOKEN` 的值。

**验收**：2 个账号输出恰好 2 行，逐字节等于 `手机号:AT:RT`；空列表时提示无可复制内容。

**实现**：`MainActivity#doCopyAll`、`CredentialStore.Account#envLine`

---

#### R-07 单账号复制 / 分享 / 删除 ✅

- 列表每项提供：复制该账号 env 行、系统分享、删除。
- 删除需二次确认；删除只影响本机 vault，**不联动修改面板变量**。

**验收**：复制内容为单行 `手机号:AT:RT`；删除后列表与 vault 同步更新；删除不发起任何网络请求。

**实现**：`MainActivity` 列表 item 点击处理、`CredentialStore#remove`

---

### C. 推送青龙面板

#### R-08 推送青龙面板（按手机号幂等合并）✅

- 面板配置：地址、用户名、密码；密码加密保存，可「清除面板配置」。
- 推送流程：
  1. `POST {base}/api/auth/login` 取 `access_token`（请求头带 `X-Client-Type: web`、`X-Client-App: daidai-panel-web`）。
  2. `GET {base}/api/envs` 读现有 `WORKBUDDY_REFRESH_TOKEN`，按换行拆行（去空行、trim）。
  3. **逐账号按手机号比对**（行内第一段冒号前）：
     - 命中 → **就地替换该行**（顺序不变），计「更新」；
     - 未命中 → **追加到末尾**，计「新增」。
  4. 合并结果整体写回：变量已存在用 `PUT {base}/api/envs/{id}`，不存在用 `POST {base}/api/envs`。
  5. `GET /api/envs` **回读校验**，确认每个推送账号的行都在。
- **关键约束（务必保持）**：
  - 其他账号的行**永远保留**，不因"App 本地没有该账号"而被删除。
  - 同一账号重复推送**不产生重复行**。
  - 变量存在时不新建第二个同名变量。
  - 变量备注保持原值，缺失时写 `WorkBuddy 多账号 手机号:AT:RT，每行一个`。
- 结果文案包含：账号总数、新增数、更新数、回读校验结论。

**验收**：模拟面板 2 行 + 推 1 新号 → 3 行且原 2 行逐字节不变；同号重推 → 行数不变、值更新；回读不一致时明确报错。

**实现**：`QlPanel#push`

---

#### R-09 面板地址规范化与校验 ✅ v1.1.0

> 来源：v1.0.0 实机推送失败 `no protocol: 192.168.124.11:5700/api/auth/login`。

- 允许用户省略协议：`192.168.124.11:5700` → 自动补 `http://`。
- 规范化步骤：去空白 → 补协议（缺失时）→ 去尾部 `/` → 剥 query / fragment → 补 `/api`（缺失时）。
- 校验并拒绝：空地址、协议非 http/https、主机名为空、端口越界、URL 内嵌账号密码。
- 保存配置与推送时都显示**规范化后的最终地址**，便于用户确认。
- 与 Web 端 `web/diag.js#normalizeBase` 保持**同一套规则**。

**验收**：`192.168.124.11:5700` → `http://192.168.124.11:5700/api`；`http://` 判为不合法；`host:99999` 判为端口不合法。

**实现**：`QlPanel#normalize`

---

### D. 诊断与可观测

#### R-10 持久化诊断日志 ✅ v1.1.0

- 位置：应用私有目录 `files/logs/app-yyyyMMdd.log`，UTF-8，按天分文件。
- 格式：`时间戳 级别 [tag] 内容`；异常额外记录异常类名、message、完整堆栈、cause 链。
- 同时写 logcat（TAG=`WorkBuddyToken`），方便 `adb logcat` 抓取。
- 滚动：保留 7 天，单文件超过 512KB 分片；总占用有上限。
- 覆盖关键路径：启动环境、发码 / 登录 / RT 刷新的每一步、面板推送全链路、
  Keystore 读写、列表操作、所有 catch 分支（29 处 catch 全部留痕）。
- HTTP 诊断：方法、URL、状态码、耗时、响应摘要（`/envs` 除外）、网络/协议/超时异常分类。
- UI：「查看」看最近日志、「导出」分享/复制、「清空」区分「仅屏幕」与「全部」。

**验收**：杀进程重启后「查看」仍能看到历史；人为断网后日志含异常类型 + 堆栈 + 请求 URL；导出内容无 AT/RT 明文。

**实现**：`AppLog`（写入 / 脱敏 / `tail` / `exportText` / `clear`）、`MainActivity` 日志区

---

#### R-11 凭据脱敏 ✅

- 手机号显示为 `155****5447`；AT/RT 显示首尾片段。
- 日志与导出统一脱敏：手机号、JWT 长串、`password=`/`token=` 等 KV、`Bearer xxx`、验证码字段。
- `/envs` 请求体与响应体**一律不落日志**（含完整 AT/RT）。
- 面板密码、短信验证码永不落盘。

**验收**：全量日志扫描 AT / RT / 手机号 / 密码明文 0 命中；导出文本可直接外发。

**实现**：`AppLog#sanitize` 及各调用点

---

### E. 桌面调试形态

#### R-12 Web 预览版（CORS 反向代理）✅

- 用途：不装 App 也能在桌面浏览器完成同样的登录 + 推送（浏览器直连 WorkBuddy 接口会被 CORS 拦截）。
- 组成：`web/index.html`（页面）+ `web/server.js`（本地 Node 反向代理，仅监听内网）+ `web/diag.js`（与 App 同规则的脱敏/规范化模块，浏览器与 Node 共用）。
- 页面日志持久化到 `localStorage`（最多 400 条），支持复制 / 下载 `.log` / 清空。
- 代理侧日志写 `web/logs/diag-yyyyMMdd.log`，**请求体（验证码 / 密码 / token）不落盘**。

**验收**：页面能完成登录与推送；推送失败时页面能看到分步诊断（规范化 → 登录 → 读取 → 合并 → 写回）。

**实现**：`web/index.html`、`web/server.js`、`web/diag.js`

---

## 3. 已撤回需求

#### X-01 「复制单行 · 手机号:AT:RT」按钮 ⛔

- **原始需求**：在主操作区增加一个独立按钮，一键复制指定账号的单行 `手机号:AT:RT`；
  多账号时弹窗选择账号，并按「最近登录的手机号」排序。
- **实现版本**：v1.0.0。
- **撤回时间**：2026-11-14。
- **撤回原因**：实机测试效果不佳，用户明确要求撤回该单独追加的需求。
- **移除范围**：
  - `activity_main.xml`：整宽主按钮 `btnCopyOne` 及其格式说明；
  - `MainActivity`：`doCopyOne()` / `copyOneNow()` / 多账号 `AlertDialog` 选择器 / `setLastPhone()` 调用；
  - `CredentialStore`：`lastPhone()` / `setLastPhone()` 及 vault 中 `lastPhone` 字段；
  - Web：`#picker` 面板、`KEY_L('wb_last_phone_v1')`、`getLastPhone()` / `copyOneNow()` / `doCopyOne()`。
- **保留说明**：`手机号:AT:RT` 这一 **env 行格式本身仍在用**（「复制全部」、列表内单账号复制、推送面板都依赖它），
  **不要**因为撤回按钮而改动格式逻辑。
- **不要再加回来**，除非用户重新提出。

---

## 4. 非功能需求

#### N-01 零第三方依赖

- Android 端不使用任何外部库（`HttpURLConnection` / `org.json` / `AndroidKeyStore` 均系统自带），
  以减小包体、避免依赖漂移。新增依赖需在本文档说明理由。

#### N-02 最小权限

- 仅声明 `INTERNET`、`ACCESS_NETWORK_STATE`。
- 因需访问内网 http 面板，保留 `usesCleartextTraffic`，但通过 `network_security_config` 约束。

#### N-03 数据不外流

- 除 WorkBuddy 官方接口与用户自填面板地址外，不访问任何第三方域名，无统计/上报 SDK。

#### N-04 失败可诊断

- 任何面向用户的失败提示，必须同时写日志（含异常类型与堆栈），
  不允许出现"只有一句 toast、日志里查不到"的情况。

#### N-05 幂等与可重入

- 重复登录同号覆盖、重复推送不产生重复行、面板变量不重复创建。

#### N-06 构建可复现

- 通过 GitHub Actions 云端构建，`gradle assembleDebug / assembleRelease` 均可独立出包；
  release 复用 debug 签名以便侧载。

---

## 5. 需求变更流程

1. 在本文档新增 `R-xx` 条目（或更新已有条目状态），写清**背景 / 行为 / 验收标准**；
2. 修改代码，`versionCode` +1、`versionName` 按语义化版本递增；
3. 更新 `docs/CHANGELOG.md`；
4. 提交并在 Actions 构建，构建通过后自动创建 Release；
5. 若为撤回需求，改为记录到「3. 已撤回需求」，**保留原始描述与撤回原因**，不要直接删除。
