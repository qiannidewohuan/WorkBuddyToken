package cn.workbuddy.token;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private EditText etPhone, etCode;
    private Button btnSend, btnLogin, btnCopyAll, btnPush, btnSettings;
    private Button btnClearLog, btnViewLog, btnExportLog;
    private CheckBox cbVerify;
    private TextView tvLog, tvEmpty, tvCount, tvVault;
    private LinearLayout accountList;

    private CredentialStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int countdown = 0;
    private boolean busy = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (countdown <= 0) {
                btnSend.setEnabled(!busy);
                btnSend.setText("发送验证码");
                return;
            }
            btnSend.setEnabled(false);
            btnSend.setText("重新发送 (" + countdown + "s)");
            countdown--;
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);                       // 必须最先：之后的每一步都会落盘
        AppLog.i("生命周期", "onCreate");
        setContentView(R.layout.activity_main);
        store = new CredentialStore(this);

        etPhone = findViewById(R.id.etPhone);
        etCode = findViewById(R.id.etCode);
        btnSend = findViewById(R.id.btnSend);
        btnLogin = findViewById(R.id.btnLogin);
        btnCopyAll = findViewById(R.id.btnCopyAll);
        btnPush = findViewById(R.id.btnPush);
        btnSettings = findViewById(R.id.btnSettings);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnViewLog = findViewById(R.id.btnViewLog);
        btnExportLog = findViewById(R.id.btnExportLog);
        cbVerify = findViewById(R.id.cbVerify);
        tvLog = findViewById(R.id.tvLog);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvCount = findViewById(R.id.tvCount);
        tvVault = findViewById(R.id.tvVault);
        accountList = findViewById(R.id.accountList);

        tvVault.setText(store.isEncrypted()
                ? "🔒 凭据已用 Android Keystore (AES-256-GCM) 加密存储"
                : "⚠️ 本机 Keystore 不可用，凭据存于应用私有目录（请勿 root / 备份）");
        AppLog.i("存储", "Keystore 可用=" + store.isEncrypted()
                + " · 已保存账号 " + store.list().size() + " 个");

        log("就绪 · 接口 " + WbApi.BASE);
        log("提示：登录前请确认本机网络可访问 WorkBuddy；验证码 5 分钟内有效。");
        log("📝 诊断日志已开启（重启不丢失）· " + AppLog.fileCount() + " 个文件 / "
                + AppLog.totalBytes() + " B · 点「查看」可复制完整堆栈");
        render();

        btnSend.setOnClickListener(v -> doSendSms());
        btnLogin.setOnClickListener(v -> doLogin());
        btnCopyAll.setOnClickListener(v -> {
            String all = store.exportLines();
            if (all.isEmpty()) { toast("还没有保存的账号"); return; }
            copy(all, "已复制全部 " + store.list().size() + " 个账号的 env 行");
        });
        btnPush.setOnClickListener(v -> doPush());
        btnSettings.setOnClickListener(v -> showPanelDialog());
        btnViewLog.setOnClickListener(v -> showLogDialog());
        btnExportLog.setOnClickListener(v -> shareText("WorkBuddy Token 诊断日志",
                AppLog.exportText(), "分享诊断日志（已脱敏）"));
        btnClearLog.setOnClickListener(v -> confirmClearLog());
    }

    @Override protected void onDestroy() {
        AppLog.i("生命周期", "onDestroy");
        super.onDestroy();
        ui.removeCallbacks(tick);
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- 发码

    private void doSendSms() {
        if (busy) return;
        final String phone = etPhone.getText().toString().trim();
        if (!WbApi.validPhone(phone)) { toast("请输入正确的 11 位手机号"); return; }

        setBusy(true, "📤 正在向 " + WbApi.maskPhone(phone) + " 发送验证码…");
        io.execute(() -> {
            try {
                JSONObject d = WbApi.sendSms(phone);
                final int exp = d.optInt("expires_in", 300);
                ui.post(() -> {
                    log("✅ 验证码已发送（有效期 " + exp + " 秒）");
                    setBusy(false, null);
                    startCountdown(60);
                    etCode.requestFocus();
                    toast("验证码已发送");
                });
            } catch (Exception e) {
                ui.post(() -> { logFail("发码", "发送验证码失败：" + e.getMessage(), e); setBusy(false, null); });
            }
        });
    }

    // ---------------------------------------------------------------- 登录

    private void doLogin() {
        if (busy) return;
        final String phone = etPhone.getText().toString().trim();
        final String code = etCode.getText().toString().trim();
        if (!WbApi.validPhone(phone)) { toast("请输入正确的 11 位手机号"); return; }
        if (code.length() < 4) { toast("请输入收到的短信验证码"); return; }
        final boolean doVerify = cbVerify.isChecked();

        setBusy(true, "🔐 正在提交登录…");
        io.execute(() -> {
            try {
                JSONObject d = WbApi.login(phone, code);
                String at = d.optString("accessToken", d.optString("access_token", ""));
                String rt = d.optString("refreshToken", d.optString("refresh_token", ""));
                if (at.isEmpty() || rt.isEmpty()) {
                    throw new Exception("响应缺少 accessToken / refreshToken");
                }

                JSONObject claims = WbApi.jwtPayload(at);
                CredentialStore.Account a = new CredentialStore.Account();
                a.phone = phone;
                a.at = at;
                a.rt = rt;
                a.user = claims.optString("preferred_username", "");
                a.sub = claims.optString("sub", "");
                a.exp = claims.optLong("exp", 0);
                a.loginAt = WbApi.nowText();

                String verifyMsg;
                if (doVerify) {
                    try {
                        JSONObject rd = WbApi.refresh(rt);
                        verifyMsg = rd.optString("accessToken", "").isEmpty()
                                ? "⚠️ RT 刷新未返回新 AT（请手动确认）"
                                : "✅ RT 刷新验证通过（已成功换发新 AT）";
                    } catch (Exception ve) {
                        verifyMsg = "⚠️ RT 刷新验证失败：" + ve.getMessage();
                        AppLog.e("登录", "RT 刷新验证失败（不影响本次登录结果）", ve);
                    }
                } else {
                    verifyMsg = "（本次未做 RT 验证）";
                }

                boolean existed = false;
                for (CredentialStore.Account x : store.list()) {
                    if (phone.equals(x.phone)) { existed = true; break; }
                }
                store.upsert(a);

                final String vm = verifyMsg;
                final boolean wasUpdate = existed;
                ui.post(() -> {
                    log("✅ 登录成功 · " + WbApi.maskPhone(phone)
                            + (a.user.isEmpty() ? "" : " · 用户 " + a.user));
                    log("   AT " + WbApi.maskToken(at) + " | RT " + WbApi.maskToken(rt));
                    log("   AT 过期 " + WbApi.expiryText(a.exp) + "（" + WbApi.remainingText(a.exp) + "）");
                    log("   " + vm);
                    log("   💾 " + (wasUpdate ? "已覆盖同手机号旧凭据" : "已新增账号"));
                    etCode.setText("");
                    setBusy(false, null);
                    render();
                    toast("凭据已加密保存");
                });
            } catch (Exception e) {
                ui.post(() -> { logFail("登录", "登录失败：" + e.getMessage(), e); setBusy(false, null); });
            }
        });
    }

    // ---------------------------------------------------------------- 青龙推送

    private void doPush() {
        if (busy) return;
        JSONObject cfg = store.panel();
        final String base = cfg.optString("base", "");
        final String user = cfg.optString("user", "");
        final String pass = cfg.optString("pass", "");
        if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            toast("请先配置青龙面板");
            AppLog.w("推送", "中止：面板配置不完整（地址/用户名/密码有空值）");
            showPanelDialog();
            return;
        }
        final List<CredentialStore.Account> accounts = store.list();
        if (accounts.isEmpty()) { toast("还没有已保存的账号"); return; }

        AppLog.i("推送", "用户点击推送 · 输入地址 " + AppLog.sanitize(base)
                + " · 本地账号 " + accounts.size() + " 个");
        setBusy(true, "☁️ 正在写入青龙面板 " + base + " …");
        io.execute(() -> {
            try {
                final String msg = QlPanel.push(base, user, pass, accounts);
                ui.post(() -> { log(msg); setBusy(false, null); toast("面板变量已更新"); });
            } catch (Exception e) {
                ui.post(() -> {
                    logFail("推送", "面板推送失败：" + e.getMessage(), e);
                    setBusy(false, null);
                    toast("推送失败，请查看诊断日志");
                });
            }
        });
    }

    private void showPanelDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_panel, null);
        final EditText eBase = v.findViewById(R.id.etBase);
        final EditText eUser = v.findViewById(R.id.etUser);
        final EditText ePass = v.findViewById(R.id.etPass);
        JSONObject cfg = store.panel();
        eBase.setText(cfg.optString("base", ""));
        eUser.setText(cfg.optString("user", ""));
        ePass.setText(cfg.optString("pass", ""));

        new AlertDialog.Builder(this)
                .setTitle("青龙面板（可选）")
                .setView(v)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        final String base = eBase.getText().toString().trim();
                        JSONObject j = new JSONObject()
                                .put("base", base)
                                .put("user", eUser.getText().toString().trim())
                                .put("pass", ePass.getText().toString().trim());
                        store.setPanel(j);
                        log("⚙️ 面板配置已加密保存：" + base);
                        AppLog.i("配置", "保存面板配置 · 地址 " + AppLog.sanitize(base)
                                + " · 用户名 " + AppLog.sanitize(j.optString("user", "")));
                        // 保存时即给出规范化预览，避免推送时才发现地址不合法
                        try { log("   规范化地址：" + QlPanel.normalize(base)); }
                        catch (Exception ne) {
                            log("⚠️ 地址校验未通过：" + ne.getMessage());
                            AppLog.e("配置", "面板地址校验未通过", ne);
                        }
                        toast("已保存");
                    } catch (Exception e) {
                        logFail("配置", "保存面板配置失败：" + e.getMessage(), e);
                        toast("保存失败：" + e.getMessage());
                    }
                })
                .setNeutralButton("清除", (d, w) -> {
                    try { store.clearPanel(); log("⚙️ 已清除面板配置"); toast("已清除"); }
                    catch (Exception e) { logFail("配置", "清除面板配置失败：" + e.getMessage(), e); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 诊断日志 UI

    private void showLogDialog() {
        final String text = AppLog.tail(60000);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10f);
        tv.setPadding(20, 16, 20, 16);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("诊断日志（已脱敏）· " + AppLog.fileCount() + " 个文件 / " + AppLog.totalBytes() + " B")
                .setView(sv)
                .setPositiveButton("复制全部", (d, w) ->
                        copy(AppLog.exportText(), "已复制完整诊断日志（" + AppLog.totalBytes() + " B）"))
                .setNeutralButton("分享", (d, w) ->
                        shareText("WorkBuddy Token 诊断日志", AppLog.exportText(), "分享诊断日志（已脱敏）"))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setItems(new CharSequence[]{
                        "仅清空屏幕显示（保留文件）",
                        "清空全部（含已保存的日志文件）"}, (d, which) -> {
                    if (which == 0) {
                        AppLog.i("AppLog", "用户清空屏幕日志（持久化文件保留）");
                        tvLog.setText("");
                        log("🧹 屏幕日志已清空，持久化日志仍保留（点「查看」可回看）");
                    } else {
                        AppLog.clear();
                        tvLog.setText("");
                        log("🧹 已清空全部日志");
                        toast("日志已清空");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 列表渲染

    private void render() {
        List<CredentialStore.Account> accounts = store.list();
        accountList.removeAllViews();
        tvCount.setText("共 " + accounts.size() + " 个账号");
        tvEmpty.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);

        for (final CredentialStore.Account a : accounts) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_account, accountList, false);
            ((TextView) row.findViewById(R.id.tvPhone)).setText(WbApi.maskPhone(a.phone));
            ((TextView) row.findViewById(R.id.tvExp)).setText(
                    "AT 至 " + WbApi.expiryText(a.exp) + " · " + WbApi.remainingText(a.exp));
            ((TextView) row.findViewById(R.id.tvUser)).setText(
                    "用户 " + (a.user.isEmpty() ? "—" : a.user)
                            + " · AT " + a.at.length() + " 字符 · 获取于 " + a.loginAt);

            row.findViewById(R.id.btnCopy).setOnClickListener(x ->
                    copy(a.envLine(), "已复制 " + WbApi.maskPhone(a.phone) + " 的 env 行"));
            row.findViewById(R.id.btnShare).setOnClickListener(x ->
                    shareText("WORKBUDDY_REFRESH_TOKEN", a.envLine(), "分享凭据（等同账号密码，谨慎）"));
            row.findViewById(R.id.btnDel).setOnClickListener(x -> confirmDelete(a));
            accountList.addView(row);
        }
    }

    private void confirmDelete(final CredentialStore.Account a) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定删除 " + WbApi.maskPhone(a.phone) + " 的本地凭据？\n"
                        + "仅删除手机上的副本，青龙面板里的变量不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        store.remove(a.phone);
                        log("🗑️ 已删除 " + WbApi.maskPhone(a.phone));
                        render();
                    } catch (Exception e) { logFail("存储", "删除账号失败：" + e.getMessage(), e); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copy(String text, String tip) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("WorkBuddy 文本", text));
            toast(tip);
        }
    }

    private void shareText(String subject, String text, String chooserTitle) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        startActivity(Intent.createChooser(i, chooserTitle));
    }

    // ---------------------------------------------------------------- 小工具

    private void setBusy(boolean b, String msg) {
        busy = b;
        btnLogin.setEnabled(!b);
        btnLogin.setText(b ? "处理中…" : "登录并获取 Token");
        btnSend.setEnabled(!b && countdown <= 0);
        btnPush.setEnabled(!b);
        btnCopyAll.setEnabled(!b);
        if (msg != null) log(msg);
    }

    private void startCountdown(int sec) {
        countdown = sec;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** 屏上摘要 + 文件里完整脱敏日志（含异常类型、message、堆栈、cause 链）。 */
    private void log(String s) {
        String old = tvLog.getText().toString();
        if (old.length() > 9000) old = old.substring(old.length() - 5000);
        tvLog.setText((old.isEmpty() ? "" : old + "\n")
                + "[" + WbApi.timeText() + "] " + s);
        AppLog.i("UI", s);
    }

    /** 失败专用：屏上给可操作提示，文件里保留完整诊断信息。 */
    private void logFail(String tag, String msg, Throwable e) {
        AppLog.e(tag, msg, e);
        log("❌ " + msg);
        log("   异常类型：" + (e == null ? "未知" : e.getClass().getSimpleName())
                + " · 完整堆栈与请求链已写入日志，点「查看」可复制");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
