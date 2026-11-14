package cn.workbuddy.token;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化诊断日志 —— 用于排查「面板推送失败」这类只给一行摘要、退出即丢失的问题。
 *
 * 设计要点：
 *  1) 落盘到应用私有目录 files/logs/app-yyyyMMdd.log，重启/崩溃后仍可读；
 *  2) 每条日志含时间戳、级别、模块标签；异常额外记录类名、message、完整堆栈与 cause 链；
 *  3) 全部内容经过 sanitize() 脱敏：手机号掩码、JWT/令牌/密码/Authorization 打码，
 *     因此导出的日志可以安全分享，不会泄露 AT:RT（等同账号密码）；
 *  4) 同时写 logcat（TAG=WorkBuddyToken），便于 adb logcat 实时抓取；
 *  5) 按天滚动、单文件 512KB 自动分片、保留 7 天，避免无限增长；
 *  6) 写日志失败绝不影响主流程（网络/存储异常一律吞掉）。
 */
public final class AppLog {

    public static final String TAG = "WorkBuddyToken";
    /** 日志功能版本；不引用 BuildConfig（AGP 8 默认不生成），避免编译期依赖。 */
    public static final String APP_VERSION = "1.1.0";
    private static final String DIR = "logs";
    private static final int KEEP_DAYS = 7;
    private static final int MAX_FILES = 14;
    private static final long MAX_FILE_BYTES = 512L * 1024;
    private static final int MAX_ENTRY_CHARS = 4000;
    private static final int MAX_EXPORT_CHARS = 180000;

    // 脱敏规则（与 web/diag.js 保持一致，改动时两端必须同步）
    private static final Pattern P_SECRET_KV = Pattern.compile(
            "(?i)(\"(?:password|pass|access_token|refresh_token|accessToken|refreshToken|token|at|rt|sms_code|code|secret|authorization)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern P_BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9_\\-.=]+");
    private static final Pattern P_JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-.=]{20,}");
    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern P_LONG = Pattern.compile("[A-Za-z0-9_\\-]{180,}");

    private static final Object LOCK = new Object();
    private static Context ctx;
    private static File dir;
    private static boolean ready;

    private AppLog() {}

    public static void init(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            ctx = c.getApplicationContext();
            try {
                dir = new File(ctx.getFilesDir(), DIR);
                if (!dir.isDirectory()) dir.mkdirs();
                ready = dir.isDirectory();
            } catch (Exception e) {
                ready = false;
                Log.e(TAG, "日志目录初始化失败: " + e.getMessage());
            }
        }
        trim();
        i("启动", "AppLog 就绪 · 版本 " + APP_VERSION
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · 目录 " + (dir == null ? "(不可用)" : dir.getAbsolutePath()));
        i("启动", "日志已自动脱敏（手机号/令牌/密码），可安全复制或分享用于排查。");
    }

    // ---------------------------------------------------------------- 写入

    public static void d(String tag, String msg) { write("D", tag, msg, null); }
    public static void i(String tag, String msg) { write("I", tag, msg, null); }
    public static void w(String tag, String msg) { write("W", tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { write("E", tag, msg, t); }

    /** 记录结构化 HTTP 结果；响应摘要会先脱敏，且调用方不应传入包含 env 值的原文。 */
    public static void http(String tag, String method, String url, int status,
                            long costMs, String note) {
        i(tag, method + " " + safeUrl(url) + " → HTTP " + status
                + " / " + costMs + "ms" + (note == null || note.isEmpty() ? "" : " / " + note));
    }

    private static void write(String level, String tag, String msg, Throwable t) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String clean = sanitize(msg == null ? "" : msg);
        StringBuilder line = new StringBuilder();
        line.append("[").append(stamp).append("] ").append(level).append("/")
                .append(tag == null ? "-" : tag).append(" ").append(clean);
        if (t != null) line.append("\n").append(stackOf(t));
        line.append("\n");
        final String text = line.toString();

        // logcat 同步输出（超长会被系统截断，文件里保留完整版）
        try {
            if ("E".equals(level)) Log.e(TAG, text);
            else if ("W".equals(level)) Log.w(TAG, text);
            else if ("D".equals(level)) Log.d(TAG, text);
            else Log.i(TAG, text);
        } catch (Exception ignored) {}

        synchronized (LOCK) {
            if (!ready || dir == null) return;
            try {
                File f = todayFile();
                if (f.exists() && f.length() + text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                    File bak = new File(dir, f.getName().replace(".log", "." + System.currentTimeMillis() + ".log"));
                    if (!f.renameTo(bak)) w("AppLog", "日志分片重命名失败，继续追加当前文件");
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                try {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                } finally {
                    try { fos.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "写日志失败: " + e.getMessage());
            }
        }
    }

    private static String stackOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 3; depth++) {
            StringWriter sw = new StringWriter();
            cur.printStackTrace(new PrintWriter(sw));
            sb.append("    异常").append(depth + 1).append(": ")
                    .append(cur.getClass().getName()).append(": ")
                    .append(sanitize(String.valueOf(cur.getMessage()))).append("\n");
            String[] lines = sanitize(sw.toString()).split("\n");
            int n = Math.min(lines.length, 25);
            for (int i = 0; i < n; i++) sb.append("    ").append(lines[i]).append("\n");
            if (lines.length > n) sb.append("    … 堆栈共 ").append(lines.length).append(" 行，已截断\n");
            cur = cur.getCause();
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 读取 / 导出

    /** 供 UI 弹窗显示：最近若干日志文件的尾部，避免一次性读入全部历史。 */
    public static String tail(int maxChars) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            if (!ready || dir == null) return "(日志目录不可用)";
            List<File> fs = logFiles();
            for (int i = fs.size() - 1; i >= 0 && sb.length() < maxChars; i--) {
                String part = tailOf(fs.get(i), 96 * 1024);
                sb.insert(0, part);
            }
        }
        String all = sb.toString();
        if (all.length() > maxChars) all = "…（更早日志已省略）\n" + all.substring(all.length() - maxChars);
        return all.isEmpty() ? "(暂无日志)" : all;
    }

    /** 完整导出文本（含头部说明），已脱敏，可直接分享。 */
    public static String exportText() {
        StringBuilder out = new StringBuilder();
        out.append("WorkBuddy Token 诊断日志\n")
                .append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n")
                .append("应用版本: ").append(APP_VERSION).append("\n")
                .append("设备信息: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append("\n")
                .append("日志目录: ").append(dir == null ? "(不可用)" : dir.getAbsolutePath()).append("\n")
                .append("脱敏说明: 手机号已掩码，JWT/令牌/密码/Authorization 已打码；仍请勿公开分享账号相关内容。\n")
                .append("------------------------------------------------------------\n");
        synchronized (LOCK) {
            if (ready && dir != null) {
                for (File f : logFiles()) {
                    out.append("### ").append(f.getName()).append(" (").append(f.length()).append(" B)\n");
                    out.append(readAll(f)).append("\n");
                }
            }
        }
        String s = out.toString();
        if (s.length() > MAX_EXPORT_CHARS) s = s.substring(0, 2000) + "\n…（中间省略）…\n" + s.substring(s.length() - MAX_EXPORT_CHARS + 2000);
        return s;
    }

    public static void clear() {
        synchronized (LOCK) {
            if (ready && dir != null) {
                for (File f : logFiles()) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }
        }
        i("AppLog", "用户已清空全部持久化日志");
    }

    public static String dirPath() { return dir == null ? "(不可用)" : dir.getAbsolutePath(); }
    public static int fileCount() { synchronized (LOCK) { return logFiles().size(); } }
    public static long totalBytes() {
        synchronized (LOCK) {
            long n = 0;
            for (File f : logFiles()) n += f.length();
            return n;
        }
    }

    // ---------------------------------------------------------------- 脱敏

    /** 导出前的统一脱敏入口；UI 层写日志时也调用它，保证文件里永远没有凭据明文。 */
    public static String sanitize(String s) {
        if (s == null) return "";
        String out = s;
        out = P_SECRET_KV.matcher(out).replaceAll("$1***$3");
        out = P_BEARER.matcher(out).replaceAll("$1***");
        out = replaceWithLength(P_JWT, out, "eyJ***");
        out = replacePhone(P_PHONE, out);
        out = P_LONG.matcher(out).replaceAll("[长串已脱敏]");
        if (out.length() > MAX_ENTRY_CHARS) out = out.substring(0, MAX_ENTRY_CHARS) + "…[已截断]";
        return out;
    }

    private static String replaceWithLength(Pattern p, String src, String prefix) {
        Matcher m = p.matcher(src);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(prefix + "[" + m.group().length() + " 字符]"));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String replacePhone(Pattern p, String src) {
        Matcher m = p.matcher(src);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String v = m.group(1);
            m.appendReplacement(b, Matcher.quoteReplacement(v.substring(0, 3) + "****" + v.substring(7)));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** URL 里若带 query 参数，只保留路径，避免把敏感 query 写进日志。 */
    private static String safeUrl(String url) {
        if (url == null) return "(空)";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q) + "?<已省略>";
        return sanitize(u);
    }

    // ---------------------------------------------------------------- 文件管理

    private static File todayFile() {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return new File(dir, "app-" + day + ".log");
    }

    private static List<File> logFiles() {
        List<File> out = new ArrayList<>();
        if (dir == null) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith("app-") && f.getName().contains(".log")) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        return out;
    }

    private static void trim() {
        synchronized (LOCK) {
            if (!ready || dir == null) return;
            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 86400000L;
            List<File> fs = logFiles();
            for (File f : fs) {
                if (f.lastModified() < cutoff) { try { f.delete(); } catch (Exception ignored) {} }
            }
            fs = logFiles();
            if (fs.size() > MAX_FILES) {
                for (int i = 0; i < fs.size() - MAX_FILES; i++) {
                    try { fs.get(i).delete(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static String tailOf(File f, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);   // 丢掉被截断的半行
            }
            return s;
        } catch (Exception e) {
            Log.e(TAG, "读取日志文件失败: " + f.getName() + " / " + e.getMessage());
            return "(读取 " + f.getName() + " 失败: " + e.getMessage() + ")\n";
        }
    }

    private static String readAll(File f) {
        return tailOf(f, Integer.MAX_VALUE);
    }
}
