package cn.workbuddy.token;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 本地凭据库：多账号 {手机号:AT:RT} 用 AndroidKeyStore 的 AES-256-GCM 密钥整体加密后
 * 存入应用私有 SharedPreferences。密钥不出 Keystore，卸载 App 即失效。
 * 设备不支持硬件 Keystore 时降级为应用私有明文，并在 UI 上明确提示。
 */
public final class CredentialStore {

    private static final String PREFS = "wb_vault_v1";
    private static final String K_BLOB = "blob";
    private static final String K_IV = "iv";
    private static final String KEY_ALIAS = "wb_vault_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    public static final class Account {
        public String phone = "";
        public String at = "";
        public String rt = "";
        public String user = "";
        public String sub = "";
        public String loginAt = "";
        public long exp = 0;

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("phone", phone).put("at", at).put("rt", rt)
                    .put("user", user).put("sub", sub)
                    .put("loginAt", loginAt).put("exp", exp);
        }

        public static Account from(JSONObject j) {
            Account a = new Account();
            a.phone = j.optString("phone", "");
            a.at = j.optString("at", "");
            a.rt = j.optString("rt", "");
            a.user = j.optString("user", "");
            a.sub = j.optString("sub", "");
            a.loginAt = j.optString("loginAt", "");
            a.exp = j.optLong("exp", 0);
            return a;
        }

        public String envLine() { return phone + ":" + at + ":" + rt; }

        public boolean looksValid() {
            return WbApi.validPhone(phone) && at.length() > 100 && rt.length() > 100;
        }
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final boolean encrypted;

    public CredentialStore(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        encrypted = ensureKey();
    }

    public boolean isEncrypted() { return encrypted; }

    private boolean ensureKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                kg.generateKey();
            }
            return true;
        } catch (Exception e) {
            AppLog.w("存储", "Keystore 自检失败，凭据将退回明文私有目录："
                    + e.getClass().getSimpleName() + " / " + e.getMessage());
            return false;
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private JSONObject root() throws Exception {
        String blob = sp.getString(K_BLOB, null);
        String iv = sp.getString(K_IV, null);
        String plain;
        if (blob == null) {
            plain = "{}";
        } else if (iv == null) {
            plain = blob;                                   // 降级：应用私有明文
        } else {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)));
            plain = new String(c.doFinal(Base64.decode(blob, Base64.DEFAULT)), StandardCharsets.UTF_8);
        }
        return new JSONObject(plain);
    }

    private void save(JSONObject root) throws Exception {
        String plain = root.toString();
        SharedPreferences.Editor e = sp.edit();
        if (encrypted) {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key());
            e.putString(K_IV, Base64.encodeToString(c.getIV(), Base64.DEFAULT));
            e.putString(K_BLOB, Base64.encodeToString(
                    c.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.DEFAULT));
        } else {
            e.remove(K_IV);
            e.putString(K_BLOB, plain);
        }
        if (!e.commit()) throw new Exception("凭据保存失败（存储空间不足？）");
    }

    public List<Account> list() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray arr = root().optJSONArray("accounts");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                Account a = Account.from(arr.optJSONObject(i));
                if (a.looksValid()) out.add(a);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 按手机号幂等覆盖：同号更新、新号追加，与 panel-env-set.js 行为一致。 */
    public void upsert(Account a) throws Exception {
        JSONObject r = root();
        JSONArray arr = r.optJSONArray("accounts");
        if (arr == null) arr = new JSONArray();
        int hit = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && a.phone.equals(o.optString("phone", ""))) { hit = i; break; }
        }
        if (hit >= 0) arr.put(hit, a.toJson()); else arr.put(a.toJson());
        r.put("accounts", arr);
        save(r);
    }

    public void remove(String phone) throws Exception {
        JSONObject r = root();
        JSONArray src = r.optJSONArray("accounts");
        JSONArray dst = new JSONArray();
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o != null && !phone.equals(o.optString("phone", ""))) dst.put(o);
            }
        }
        r.put("accounts", dst);
        save(r);
    }

    /** 导出为 WORKBUDDY_REFRESH_TOKEN 的多行值：一行一个账号。 */
    public String exportLines() {
        StringBuilder sb = new StringBuilder();
        for (Account a : list()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.envLine());
        }
        return sb.toString();
    }

    /** 青龙面板连接配置（可选），与账号数据一起加密保存。 */
    public JSONObject panel() {
        try {
            JSONObject p = root().optJSONObject("panel");
            return p != null ? p : new JSONObject();
        } catch (Exception e) {
            AppLog.w("存储", "读取面板配置失败，按空配置处理："
                    + e.getClass().getSimpleName() + " / " + e.getMessage());
            return new JSONObject();
        }
    }

    public void setPanel(JSONObject p) throws Exception {
        JSONObject r = root();
        r.put("panel", p);
        save(r);
    }

    public void clearPanel() throws Exception {
        JSONObject r = root();
        r.remove("panel");
        save(r);
    }
}
