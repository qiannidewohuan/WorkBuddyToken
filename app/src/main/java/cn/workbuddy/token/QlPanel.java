package cn.workbuddy.token;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 可选能力：把本地多账号凭据按手机号幂等写入青龙（DainDai-Panel）的
 * WORKBUDDY_REFRESH_TOKEN 变量，行为与 panel-env-set.js 一致。
 * 面板通常是局域网 http 地址，故 Manifest 允许 cleartext；WorkBuddy 接口始终 https。
 *
 * v1.1.0：修复「面板推送失败: no protocol: 192.168.1.10:5700/api/auth/login」
 * —— 用户填的地址常省略 http://，旧版直接 new URL() 抛 MalformedURLException，
 * 屏上只显示一行摘要、无堆栈、重启即丢。现在自动补全协议并全链路写 AppLog。
 */
public final class QlPanel {

    public static final String VAR = "WORKBUDDY_REFRESH_TOKEN";
    private static final String REMARKS = "WorkBuddy 多账号 手机号:AT:RT，每行一个";
    private static final String TAG = "面板HTTP";

    private QlPanel() {}

    /** 带诊断信息的失败：message 给屏上看，cause + 完整堆栈写入 AppLog。 */
    public static final class PanelError extends Exception {
        public PanelError(String message) { super(message); }
        public PanelError(String message, Throwable cause) { super(message, cause); }
    }

    public static String push(String base, String user, String pass,
                              List<CredentialStore.Account> accounts) throws Exception {
        final long t0 = System.currentTimeMillis();
        AppLog.i("推送", "=== 开始推送青龙 · 本地账号 "
                + (accounts == null ? 0 : accounts.size()) + " 个 · 输入地址 " + AppLog.sanitize(base) + " ===");
        if (accounts == null || accounts.isEmpty()) {
            AppLog.w("推送", "中止：没有可推送的账号");
            throw new PanelError("没有可推送的账号");
        }
        if (base == null || base.trim().isEmpty()) {
            AppLog.w("推送", "中止：面板地址为空");
            throw new PanelError("面板地址为空");
        }
        if (user == null || user.trim().isEmpty() || pass == null || pass.isEmpty()) {
            AppLog.w("推送", "中止：面板用户名或密码为空");
            throw new PanelError("面板用户名或密码为空");
        }

        final String api = normalize(base);
        try {
            JSONObject body = new JSONObject()
                    .put("username", user).put("password", pass).put("totp_code", "");
            String loginText = request(api + "/auth/login", "POST", body, null);
            final String token;
            try {
                token = new JSONObject(loginText).optString("access_token", "");
            } catch (Exception pe) {
                throw fail("面板登录响应不是合法 JSON：" + brief(loginText), pe);
            }
            if (token.isEmpty()) {
                throw new PanelError("面板登录失败（账号密码错误或触发登录频控，请稍后再试）："
                        + AppLog.sanitize(brief(loginText)));
            }
            AppLog.i("推送", "登录成功 · 已取得访问令牌 " + WbApi.maskToken(token));

            JSONArray envs = asArray(request(api + "/envs", "GET", null, token));
            JSONObject cur = null;
            for (int i = 0; i < envs.length(); i++) {
                JSONObject o = envs.optJSONObject(i);
                if (o != null && VAR.equals(o.optString("name", ""))) { cur = o; break; }
            }

            List<String> lines = new ArrayList<>();
            if (cur != null) {
                for (String s : cur.optString("value", "").replace("\r", "").split("\n")) {
                    String t = s.trim();
                    if (!t.isEmpty()) lines.add(t);
                }
            }
            AppLog.i("推送", "读取环境变量：" + (cur == null ? "变量尚不存在" : "已存在")
                    + " · 面板现有 " + lines.size() + " 行 · env 总数 " + envs.length()
                    + "（env 值不写入日志）");

            int added = 0, updated = 0;
            for (CredentialStore.Account a : accounts) {
                int hit = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (phoneOf(lines.get(i)).equals(a.phone)) { hit = i; break; }
                }
                if (hit >= 0) { lines.set(hit, a.envLine()); updated++; }
                else { lines.add(a.envLine()); added++; }
            }

            StringBuilder sb = new StringBuilder();
            for (String l : lines) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
            String value = sb.toString();
            AppLog.i("推送", "合并完成 · 最终 " + lines.size() + " 行（新增 " + added + " / 更新 " + updated + "）");

            String resultText;
            if (cur == null) {
                resultText = request(api + "/envs", "POST",
                        new JSONObject().put("name", VAR).put("value", value).put("remarks", REMARKS), token);
            } else {
                JSONObject payload = new JSONObject(cur.toString())
                        .put("name", VAR).put("value", value)
                        .put("remarks", cur.optString("remarks", REMARKS));
                resultText = request(api + "/envs/" + cur.optInt("id", 0), "PUT", payload, token);
            }
            if (resultText == null) resultText = "";
            AppLog.i("推送", "写入请求完成 · 响应 " + resultText.length() + " 字符（响应体不写入日志）");

            // 回读校验：每个本地账号的整行都必须在变量里
            JSONArray after = asArray(request(api + "/envs", "GET", null, token));
            String readBack = "";
            for (int i = 0; i < after.length(); i++) {
                JSONObject o = after.optJSONObject(i);
                if (o != null && VAR.equals(o.optString("name", ""))) { readBack = o.optString("value", ""); break; }
            }
            boolean ok = true;
            for (CredentialStore.Account a : accounts) {
                if (!readBack.contains(a.envLine())) { ok = false; break; }
            }
            AppLog.i("推送", "回读校验：" + (ok ? "✅ 与本地一致" : "❌ 不一致")
                    + " · 总耗时 " + (System.currentTimeMillis() - t0) + "ms");

            return "☁️ 面板变量已更新：" + VAR
                    + "\n   账号数 " + lines.size() + "（新增 " + added + " / 更新 " + updated + "）"
                    + "\n   回读校验：" + (ok ? "✅ 与本地一致" : "❌ 不一致，请到面板手动核对")
                    + "\n   响应：" + resultText.substring(0, Math.min(80, resultText.length()));
        } catch (Exception e) {
            AppLog.e("推送", "推送全流程失败 · 耗时 " + (System.currentTimeMillis() - t0)
                    + "ms · 面板 " + api + "（完整请求链与堆栈见上方记录）", e);
            throw e;
        }
    }

    /**
     * 地址规范化：允许省略协议、允许带 /api、允许尾斜杠。
     * 例：192.168.1.10:5700 → http://192.168.1.10:5700/api
     */
    public static String normalize(String base) throws Exception {
        String raw = base == null ? "" : base;
        String b = raw.trim().replaceAll("\\s+", "");
        if (b.isEmpty()) throw fail("面板地址为空", null);

        if (!b.matches("(?i)^https?://.*")) {
            b = "http://" + b;
            AppLog.i("面板地址", "输入地址缺少协议，已自动补全 http:// ："
                    + AppLog.sanitize(raw) + " → " + AppLog.sanitize(b));
        }
        b = b.replaceFirst("[?#].*$", "");
        if (!b.matches("(?i)^https?://[^/\\s]+.*")) {
            throw fail("面板地址缺少主机名（示例 http://192.168.1.10:5700）：" + AppLog.sanitize(raw), null);
        }
        if (b.matches("(?i)^https?://[^/@]+:[^/@]+@.*")) {
            throw fail("面板地址不支持内嵌用户名密码，请单独填写用户名与密码", null);
        }
        b = b.replaceAll("/+$", "");
        if (!b.toLowerCase(Locale.ROOT).endsWith("/api")) b += "/api";

        try {
            URL u = new URL(b);
            if (u.getHost() == null || u.getHost().isEmpty()) {
                throw fail("面板地址缺少主机名：" + AppLog.sanitize(raw), null);
            }
            int port = u.getPort();
            if (port < -1 || port > 65535) {
                throw fail("面板端口不合法（应为 1-65535）：" + AppLog.sanitize(raw), null);
            }
            if (!"http".equalsIgnoreCase(u.getProtocol()) && !"https".equalsIgnoreCase(u.getProtocol())) {
                throw fail("面板地址协议不支持（仅 http/https）：" + AppLog.sanitize(raw), null);
            }
        } catch (MalformedURLException e) {
            throw fail("面板地址格式不合法（示例 http://192.168.1.10:5700）：" + e.getMessage(), e);
        }
        AppLog.i("面板地址", "规范化结果：" + AppLog.sanitize(raw) + " → " + AppLog.sanitize(b));
        return b;
    }

    private static String phoneOf(String line) {
        int p = line.indexOf(':');
        return p > 0 ? line.substring(0, p).trim() : "";
    }

    private static JSONArray asArray(String s) throws Exception {
        try { return new JSONArray(s); } catch (Exception ignored) {}
        try {
            JSONObject j = new JSONObject(s);
            JSONArray a = j.optJSONArray("data");
            if (a == null) a = j.optJSONArray("envs");
            return a != null ? a : new JSONArray();
        } catch (Exception e) {
            throw fail("面板 /envs 响应解析失败：" + AppLog.sanitize(brief(s)), e);
        }
    }

    private static String request(String urlStr, String method, JSONObject body, String bearer)
            throws Exception {
        final long t0 = System.currentTimeMillis();
        final String safe = safeUrl(urlStr);
        AppLog.d(TAG, method + " " + safe + " 开始");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Client-Type", "web");
            conn.setRequestProperty("X-Client-App", "daidai-panel-web");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);

            if (body != null) {
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(out.length);
                OutputStream os = conn.getOutputStream();
                try { os.write(out); os.flush(); } finally { try { os.close(); } catch (Exception ignored) {} }
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (is != null) try { is.close(); } catch (Exception ignored) {}

            // /envs 的响应/请求体含完整 AT:RT，绝不写摘要；其余响应摘要先脱敏
            String note = "响应 " + text.length() + " 字符";
            if (urlStr.contains("/envs")) {
                note += " / 响应体含凭据，已省略";
            } else {
                note += " / 摘要 " + AppLog.sanitize(brief(text));
            }
            AppLog.http(TAG, method, urlStr, status, System.currentTimeMillis() - t0, note);

            if (status < 200 || status >= 300) {
                throw fail("HTTP " + status + "：" + AppLog.sanitize(brief(text)), null);
            }
            return text;
        } catch (PanelError e) {
            AppLog.d(TAG, "错误已由 fail() 完整记录，直接上抛");
            throw e;                    // fail() 已记录堆栈
        } catch (MalformedURLException e) {
            throw fail("面板地址无效（" + e.getMessage() + "）；请填写 http:// 开头的完整地址", e);
        } catch (SocketTimeoutException e) {
            throw fail("连接面板超时（12s）：手机需与面板在同一局域网，地址示例 http://192.168.x.x:5700", e);
        } catch (UnknownHostException e) {
            throw fail("找不到面板主机：请检查地址拼写与手机网络（" + e.getMessage() + "）", e);
        } catch (ConnectException e) {
            throw fail("无法连接面板：检查地址、端口与网络（" + e.getMessage() + "）", e);
        } catch (javax.net.ssl.SSLException e) {
            throw fail("HTTPS 握手失败：局域网面板通常应使用 http://（" + e.getMessage() + "）", e);
        } catch (java.io.IOException e) {
            throw fail("面板网络请求失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage(), e);
        } catch (Exception e) {
            throw fail("面板请求异常（" + e.getClass().getSimpleName() + "）：" + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static PanelError fail(String msg, Throwable cause) {
        AppLog.e(TAG, msg, cause);
        return new PanelError(msg, cause);
    }

    /** 摘要固定很短；注意先对全文脱敏再截断，避免 token 被切断后漏掉打码。 */
    private static String brief(String s) {
        if (s == null) return "(空)";
        String safe = AppLog.sanitize(s);
        return safe.substring(0, Math.min(120, safe.length()));
    }

    private static String safeUrl(String url) {
        if (url == null) return "(空)";
        int q = url.indexOf('?');
        return AppLog.sanitize(q >= 0 ? url.substring(0, q) + "?<已省略>" : url);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
