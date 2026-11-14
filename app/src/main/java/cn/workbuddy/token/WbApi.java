package cn.workbuddy.token;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 与 workbuddy_login.py 逐字段对齐的接口封装。
 * 三个接口：send-sms / login/token / token/refresh
 * 所有 public 方法必须在子线程调用（Android 主线程禁止网络）。
 */
public final class WbApi {

    public static final String BASE = "https://www.workbuddy.cn";
    public static final String SEND_SMS_URL = BASE + "/v2/plugin/login/send-sms";
    public static final String LOGIN_URL = BASE + "/v2/plugin/login/token";
    public static final String REFRESH_URL = "https://copilot.tencent.com/v2/plugin/auth/token/refresh";
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/138.0.7204.251 Safari/537.36";

    private WbApi() {}

    public static final class ApiException extends Exception {
        public ApiException(String message) { super(message); }
    }

    /** 发送短信验证码，成功返回 data（含 expires_in）。 */
    public static JSONObject sendSms(String phone) throws Exception {
        return call(SEND_SMS_URL, new JSONObject().put("phone", phone), null, 20000, "发送验证码");
    }

    /** 用手机号 + 验证码登录，成功返回 data（含 accessToken / refreshToken）。 */
    public static JSONObject login(String phone, String code) throws Exception {
        JSONObject body = new JSONObject()
                .put("login_method", "phone")
                .put("phone", phone)
                .put("sms_code", code);
        return call(LOGIN_URL, body, null, 30000, "登录");
    }

    /** 用 RT 试刷一次，验证凭据真的可用；成功返回 data（含新的 AT/RT）。 */
    public static JSONObject refresh(String refreshToken) throws Exception {
        return call(REFRESH_URL, new JSONObject(), refreshToken, 20000, "刷新验证");
    }

    private static JSONObject call(String urlStr, JSONObject body, String refreshToken,
                                   int timeoutMs, String tag) throws Exception {
        String payload = body.toString();
        IOException lastIo = null;
        final long t0 = System.currentTimeMillis();
        AppLog.d("WB接口", tag + " 开始 · POST " + urlStr + " · 请求体 " + payload.length() + " 字符（内容不记录）");

        // 连接层偶发失败（代理 / DNS / 短暂断网）重试一次，避免把网络问题误判成验证码错误
        for (int attempt = 0; attempt < 2; attempt++) {
            final long a0 = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                if (refreshToken == null) {
                    // 对应 py 的 _headers()
                    conn.setRequestProperty("Origin", BASE);
                    conn.setRequestProperty("Referer", BASE + "/");
                    conn.setRequestProperty("User-Agent", UA);
                } else {
                    // 对应 py 的 verify_rt()，不额外带 Origin/Referer
                    conn.setRequestProperty("X-Refresh-Token", refreshToken);
                    conn.setRequestProperty("X-Auth-Refresh-Source", "plugin");
                }

                byte[] out = payload.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                OutputStream os = conn.getOutputStream();
                try { os.write(out); os.flush(); } finally { try { os.close(); } catch (IOException ignored) {} }

                int status = conn.getResponseCode();
                InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = readAll(is);
                if (is != null) try { is.close(); } catch (IOException ignored) {}

                JSONObject resp;
                boolean sensitive = urlStr.contains("/login/token") || urlStr.contains("/token/refresh");
                AppLog.http("WB接口", "POST", urlStr, status, System.currentTimeMillis() - a0,
                        "响应 " + text.length() + " 字符"
                                + (sensitive ? " / 响应含令牌，摘要已省略" : " / 摘要 " + AppLog.sanitize(text.length() > 120 ? text.substring(0, 120) : text))
                                + " / 第 " + (attempt + 1) + " 次");
                try {
                    resp = new JSONObject(text);
                } catch (Exception pe) {
                    String brief = text == null ? "(空)" : AppLog.sanitize(text.substring(0, Math.min(90, text.length())));
                    AppLog.e("WB接口", tag + " 响应不是 JSON（HTTP " + status + "）", pe);
                    throw new ApiException(tag + "失败：HTTP " + status + "，响应不是 JSON：" + brief);
                }

                if (resp.optInt("code", -1) != 0) {
                    String msg = resp.optString("msg", "未知错误");
                    AppLog.w("WB接口", tag + " 业务失败 · code=" + resp.optInt("code", -1)
                            + " · msg=" + AppLog.sanitize(msg));
                    throw new ApiException(tag + "失败：" + msg + hint(msg));
                }
                JSONObject data = resp.optJSONObject("data");
                AppLog.i("WB接口", tag + " 成功 · 总耗时 " + (System.currentTimeMillis() - t0) + "ms");
                return data != null ? data : new JSONObject();

            } catch (ApiException e) {
                AppLog.d("WB接口", tag + " 业务错误不重试，直接上抛（错误已在上游记录）");
                throw e;                       // 业务错误不重试
            } catch (IOException e) {
                lastIo = e;                    // 仅连接层错误重试
                AppLog.e("WB接口", tag + " 第 " + (attempt + 1) + " 次连接失败（"
                        + e.getClass().getSimpleName() + "），"
                        + (attempt == 0 ? "将重试一次" : "重试已用尽"), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        String why = lastIo == null ? "未知" : lastIo.getClass().getSimpleName() + ": " + lastIo.getMessage();
        AppLog.e("WB接口", tag + " 最终失败 · 总耗时 " + (System.currentTimeMillis() - t0)
                + "ms · " + AppLog.sanitize(why), lastIo);
        throw new ApiException(tag + "失败：网络连接异常（" + why + "）\n💡 请检查网络，或切换 WiFi / 移动数据后重试");
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 把服务端的错误文案翻译成可操作提示，与 py 的分支一致。 */
    private static String hint(String msg) {
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.contains("keycloak spi") || low.contains("400")) {
            return "\n💡 手机号格式可能有误，或该号码暂不支持短信登录";
        }
        if (msg.contains("频繁") || low.contains("frequent") || low.contains("too many")) {
            return "\n💡 操作过于频繁，请稍后再试";
        }
        if (msg.contains("验证码") || low.contains("expire") || low.contains("code")) {
            return "\n💡 验证码可能有误或已过期，请重新发送后尽快输入";
        }
        return "";
    }

    /** 解析 JWT payload（不校验签名，仅读取展示用的 claim）。 */
    public static JSONObject jwtPayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return new JSONObject();
            String seg = parts[1];
            while (seg.length() % 4 != 0) seg += "=";
            byte[] raw = Base64.decode(seg, Base64.URL_SAFE | Base64.NO_WRAP);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("WB接口", "JWT payload 解析失败（仅影响展示，不影响凭据保存）："
                    + e.getClass().getSimpleName() + " / " + e.getMessage());
            return new JSONObject();
        }
    }

    public static String expiryText(long expSeconds) {
        if (expSeconds <= 0) return "未知";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(expSeconds * 1000L));
    }

    public static String remainingText(long expSeconds) {
        long diff = expSeconds * 1000L - System.currentTimeMillis();
        if (expSeconds <= 0) return "";
        if (diff <= 0) return "已过期";
        long days = diff / 86400000L;
        if (days >= 1) return "剩余 " + days + " 天";
        long hours = diff / 3600000L;
        if (hours >= 1) return "剩余 " + hours + " 小时";
        return "剩余 " + Math.max(1, diff / 60000L) + " 分钟";
    }

    public static String nowText() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public static String timeText() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public static String maskPhone(String p) {
        if (p == null || p.length() < 7) return String.valueOf(p);
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    /** 日志中只显示掩码，绝不打印完整 token。 */
    public static String maskToken(String t) {
        if (t == null || t.isEmpty()) return "(空)";
        String head = t.length() > 8 ? t.substring(0, 8) : t;
        return head + "…(" + t.length() + " 字符)";
    }

    public static boolean validPhone(String p) {
        return p != null && p.matches("^1[3-9]\\d{9}$");
    }
}
