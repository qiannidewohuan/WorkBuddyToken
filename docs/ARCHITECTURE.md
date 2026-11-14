# 技术架构与关键决策

> 上游依据：本项目**依据 [L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily) 打包自用**，
> 是它的「取令牌」配套工具（把 `手机号:AT:RT` 写进面板 `WORKBUDDY_REFRESH_TOKEN`），
> 不修改、不内置上游脚本；定位与边界见 [REQUIREMENTS.md §1.4](REQUIREMENTS.md)。

## 1. 模块划分

```
MainActivity            界面与流程编排（登录、列表、面板配置、日志区）
   ├── WbApi           WorkBuddy 登录接口封装（发码 / 登录 / RT 刷新 / JWT 解析）
   ├── CredentialStore 加密多账号存储（AndroidKeyStore + AES-256-GCM）
   ├── QlPanel         青龙面板推送（地址规范化 + 按手机号幂等合并）
   └── AppLog          持久化诊断日志（写入 / 脱敏 / 查看 / 导出 / 清理）
```

依赖方向单向向下，`MainActivity` 是唯一持有 `Activity` 上下文的类；
其余三个类都不持有 UI 引用，全部通过返回值/异常上报，便于单独测试。

## 2. 登录与推送数据流

```
[手机号] --send-sms--> workbuddy.cn/v2/plugin/login/send-sms
[手机号+验证码] --token--> workbuddy.cn/v2/plugin/login/token
                                  ↓ data.accessToken / data.refreshToken
                     (可选) --refresh--> copilot.tencent.com/v2/plugin/auth/token/refresh
                                  ↓ 新 AT/RT 覆盖
                     CredentialStore（AES-256-GCM 加密落盘）
                                  ↓ 「复制全部」/「推送青龙」
       手机号:AT:RT（每行一个）→ 青龙 /api/envs 按行幂等合并 → PUT /api/envs/{id}
```

## 3. 安全模型

| 数据 | 存放 | 保护方式 |
|---|---|---|
| AT / RT / 面板密码 | 应用私有目录 vault | AndroidKeyStore 非导出密钥，AES-256-GCM，`allowBackup=false` |
| 短信验证码 | 仅内存 | 不落盘、不进日志 |
| 日志文件 | 应用私有目录 `files/logs` | 写入前统一脱敏，`/envs` 请求/响应体不记录 |
| 导出内容 | 用户主动触发 | 导出前再做一次 `sanitize` |

不引入网络层证书固定（自建面板多为内网 http，固定证书会让可用性变差），
改为「只访问用户显式填写的地址 + 官方域名」这一约束控制外联面。

## 4. 关键决策与踩坑记录

### 4.1 为什么用短信登录而不是提取桌面端凭据

桌面端凭据以 `$wbEncrypted` 落盘，密钥经 `AtRestCrypto` / `atRestSecretKey` 由系统加密层托管
（`process._linkedBinding('electron_browser_workbuddy_storage')`），密钥不落盘、不可导出。
离线解密不可行，故只能让用户在手机端自行登录换取。

### 4.2 为什么不用第三方网络库

系统 `HttpURLConnection` 足够覆盖本场景，零依赖可显著降低包体与供应链风险；
代价是需要手写重试与错误分类，这部分集中在 `WbApi#call` / `QlPanel#request` 两处。

### 4.3 面板地址必须先规范化（v1.1.0 修复的线上问题）

实机报错：`no protocol: 192.168.1.10:5700/api/auth/login`。

根因：用户输入时省略 `http://`，旧代码只补 `/api` 就直接 `new URL()`，抛 `MalformedURLException`，
而当时的日志只有一句 toast，无堆栈、无请求上下文，问题定位不到。

修复要点（Android 与 Web 两端同规则）：
1. 缺协议自动补 `http://`；
2. 去尾部 `/`、剥 query / fragment，再补 `/api`；
3. 校验协议、主机名、端口范围，拒绝 URL 内嵌账号密码；
4. 保存配置与推送时把规范化后的地址回显给用户。

### 4.4 CORS：浏览器直连不通，必须有代理

WorkBuddy 登录接口不允许浏览器跨域直连。Web 形态用本地 Node 反向代理转发，
App 形态不受 CORS 约束（原生请求），因此无需代理。

### 4.5 云构建的两个坑

- **不要用 `android-actions/setup-android@v3`**：它内部执行 `sdkmanager tools`，
  而新版 cmdline-tools(16.0) 已移除旧 `tools` 包，会报 `Failed to find package 'tools'` 导致 job 失败。
  改为直接用 runner 预装 SDK 里的 `sdkmanager` 安装 `platforms;android-34` / `build-tools;34.0.0`。
- **`setup-java` + Gradle 版本要显式指定**：用 `gradle/actions/setup-gradle@v4` 指定 Gradle 8.9，
  与 AGP 版本匹配，避免 runner 自带 Gradle 版本漂移。

### 4.6 空仓库初始化

`auto_init:false` 建出的仓库没有 commit，Git Data API 的 `POST /git/blobs` 会返回
`409 Git Repository is empty.`。需先用 Contents API 写一次 `.gitignore` 造出初始 commit。

### 4.7 RT 刷新会轮转

RT 刷新成功后旧 RT 立即失效。因此刷新必须"要么成功并落库最新值，要么完全不刷新"，
不能在刷新失败时保留半新半旧的状态。

## 5. 数据契约（务必与 Three 端一致）

| 形态 | 格式 |
|---|---|
| env 行 | `手机号:AT:RT`（冒号分隔，无空格） |
| 多账号 | 换行 `\n` 分隔，行尾无空行 |
| 变量名 | `WORKBUDDY_REFRESH_TOKEN` |
| 面板写回 | 存在 → `PUT /api/envs/{id}`；不存在 → `POST /api/envs` |

Android / Web 页面 / 代理三处的合并规则必须一致：**按手机号就地覆盖，新号追加末尾，其余行不动**。
改动任一处都要同步另两处，并补一条回归用例。
