/*!
 * WorkBuddy 凭据助手 · 诊断工具（UMD：浏览器 <script> 与 Node require 共用同一份实现）
 *
 * 提供：
 *   sanitize()      统一脱敏，保证日志/导出文件里没有手机号、JWT、密码、Authorization
 *   normalizeBase() 面板地址规范化，修复「no protocol: 192.168.1.10:5700/api/auth/login」
 *   formatLine()    结构化日志行
 *   safeUrl()       去掉 URL query，避免把敏感参数写进日志
 *
 * 与 Android 端 AppLog.java / QlPanel.java 的规则保持一致，改动需两端同步。
 */
(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.WbDiag = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var VERSION = '1.1.0';

  var SECRET_KV = /("(?:password|pass|access_token|refresh_token|accessToken|refreshToken|token|at|rt|sms_code|code|secret|authorization)"\s*:\s*")([^"]*)(")/gi;
  var BEARER = /(Bearer\s+)[A-Za-z0-9_\-.=]+/gi;
  var JWT = /eyJ[A-Za-z0-9_\-.=]{20,}/g;
  var PHONE = /(^|[^\d])(1[3-9]\d{9})([^\d]|$)/g;
  var LONG = /[A-Za-z0-9_\-]{180,}/g;

  /** 脱敏：顺序固定，先整段键值，再 Bearer / JWT / 手机号 / 超长串。 */
  function sanitize(s) {
    if (s === null || s === undefined) return '';
    var out = String(s);
    out = out.replace(SECRET_KV, '$1***$3');
    out = out.replace(BEARER, '$1***');
    out = out.replace(JWT, function (m) { return 'eyJ***[' + m.length + ' 字符]'; });
    out = out.replace(PHONE, function (m, a, p, b) {
      return a + p.slice(0, 3) + '****' + p.slice(7) + b;
    });
    out = out.replace(LONG, '[长串已脱敏]');
    if (out.length > 4000) out = out.slice(0, 4000) + '…[已截断]';
    return out;
  }

  /**
   * 规范化青龙面板地址。
   * 允许省略协议（自动补 http://）、允许已带 /api、允许尾斜杠。
   * 192.168.1.10:5700 → http://192.168.1.10:5700/api
   */
  function normalizeBase(raw) {
    var b = String(raw === null || raw === undefined ? '' : raw).trim().replace(/\s+/g, '');
    if (!b) throw new Error('面板地址为空');
    if (!/^https?:\/\//i.test(b)) b = 'http://' + b;
    b = b.replace(/[?#].*$/, '');
    if (!/^https?:\/\/[^/\s]+/i.test(b)) {
      throw new Error('面板地址缺少主机名（示例 http://192.168.1.10:5700）');
    }
    if (/^https?:\/\/[^/@]+:[^/@]+@/i.test(b)) {
      throw new Error('面板地址不支持内嵌用户名密码，请在下方单独填写');
    }
    b = b.replace(/\/+$/, '');
    if (!/\/api$/i.test(b)) b += '/api';
    var u;
    try { u = new URL(b); }
    catch (e) { throw new Error('面板地址格式不合法（示例 http://192.168.1.10:5700）：' + e.message); }
    if (!u.hostname) throw new Error('面板地址缺少主机名');
    if (u.port && (Number(u.port) < 1 || Number(u.port) > 65535)) throw new Error('面板端口不合法（应为 1-65535）');
    if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new Error('面板地址协议不支持（仅 http/https）');
    return u.toString().replace(/\/+$/, '');
  }

  function safeUrl(url) {
    if (!url) return '(空)';
    var s = String(url), q = s.indexOf('?');
    return sanitize(q >= 0 ? s.slice(0, q) + '?<已省略>' : s);
  }

  function pad(n, w) {
    var s = String(n);
    while (s.length < w) s = '0' + s;
    return s;
  }

  function stamp(d) {
    d = d || new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1, 2) + '-' + pad(d.getDate(), 2)
      + ' ' + pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2)
      + '.' + pad(d.getMilliseconds(), 3);
  }

  function formatLine(level, tag, msg, date) {
    return '[' + stamp(date) + '] ' + level + '/' + (tag || '-') + ' ' + sanitize(msg);
  }

  return {
    VERSION: VERSION,
    sanitize: sanitize,
    normalizeBase: normalizeBase,
    safeUrl: safeUrl,
    stamp: stamp,
    formatLine: formatLine,
  };
});
