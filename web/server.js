#!/usr/bin/env node
/**
 * WorkBuddy 凭据助手 —— 本地反向代理（零依赖）
 *
 * 为什么需要它：www.workbuddy.cn 的登录接口不返回任何 CORS 头，
 * 浏览器里的 JS 无法直接跨域调用。本服务在服务端转发请求，
 * 页面与接口同源，因此不存在跨域问题。Android App 则直接由原生层发请求，不需要本服务。
 *
 * 用法：
 *   node server.js                  # 只监听 127.0.0.1:8787（本机预览，安全）
 *   WB_LAN=1 node server.js        # 监听 0.0.0.0，手机同一 WiFi 可访问
 *   PORT=9000 node server.js       # 改端口
 *
 * 安全：请求体（验证码 / 面板密码 / token）绝不写入日志或磁盘。
 * 仅写脱敏后的诊断日志到 web/logs/diag-yyyyMMdd.log（含 HTTP 状态、耗时、异常堆栈）。
 * 仅监听内网，用毕即关；不要映射到公网。
 */
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { sanitize, normalizeBase, formatLine } = require('./diag.js');

const PORT = parseInt(process.env.PORT || '8787', 10);
const LAN = process.env.WB_LAN === '1';
const HOST = LAN ? '0.0.0.0' : '127.0.0.1';

const BASE = 'https://www.workbuddy.cn';
const SEND_SMS = BASE + '/v2/plugin/login/send-sms';
const LOGIN = BASE + '/v2/plugin/login/token';
const REFRESH = 'https://copilot.tencent.com/v2/plugin/auth/token/refresh';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
  + '(KHTML, like Gecko) Chrome/138.0.7204.251 Safari/537.36';
const COMMON = {
  'Content-Type': 'application/json',
  'Accept': 'application/json, text/plain, */*',
  'Origin': BASE,
  'Referer': BASE + '/',
  'User-Agent': UA,
};
const REFRESH_HEADERS = {
  'Content-Type': 'application/json',
  'X-Auth-Refresh-Source': 'plugin',
};
const VAR = 'WORKBUDDY_REFRESH_TOKEN';

function request(urlStr, method, headers, obj, timeout = 25000) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    let u;
    try { u = new URL(urlStr); }
    catch (e) { reject(new Error('请求地址无效（' + e.message + '）：' + sanitize(urlStr))); return; }
    const payload = obj === null ? null : Buffer.from(JSON.stringify(obj), 'utf8');
    const h = Object.assign({}, headers);
    if (payload) h['Content-Length'] = payload.length;
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.request({
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      method,
      headers: h,
      timeout,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode,
        text: Buffer.concat(chunks).toString('utf8'),
        ms: Date.now() - t0,
      }));
    });
    req.on('timeout', () => req.destroy(new Error('请求超时（' + timeout + 'ms）')));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

/** 只写脱敏后的诊断行；请求体永远不进入这里。 */
function fileLog(text) {
  try {
    const d = path.join(__dirname, 'logs');
    if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
    const day = new Date().toISOString().slice(0, 10);
    fs.appendFileSync(path.join(d, 'diag-' + day + '.log'),
      formatLine('I', '代理', text) + '\n', 'utf8');
  } catch (e) { /* 写日志失败绝不影响主流程 */ }
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function parseJson(text) {
  try { return JSON.parse(text); } catch (e) { return null; }
}

function hint(msg) {
  const low = String(msg || '').toLowerCase();
  if (low.includes('keycloak spi') || low.includes('400')) return ' 💡 手机号格式可能有误，或该号码暂不支持短信登录';
  if (String(msg).includes('频繁') || low.includes('frequent') || low.includes('too many')) return ' 💡 操作过于频繁，请稍后再试';
  if (String(msg).includes('验证码') || low.includes('expire') || low.includes('code')) return ' 💡 验证码可能有误或已过期';
  return '';
}

function lanIPs() {
  const out = [];
  const ifs = os.networkInterfaces();
  for (const k of Object.keys(ifs)) {
    for (const x of ifs[k] || []) {
      if (x.family === 'IPv4' && !x.internal) out.push(x.address);
    }
  }
  return out;
}

// ---------------------------------------------------------------- 业务端点

async function apiSendSms(j) {
  const r = await request(SEND_SMS, 'POST', COMMON, { phone: j.phone || '' }, 20000);
  const d = parseJson(r.text) || {};
  if (d.code !== 0) return { ok: false, msg: (d.msg || ('HTTP ' + r.status)) + hint(d.msg) };
  return { ok: true, expires_in: (d.data || {}).expires_in || 300 };
}

async function apiLogin(j) {
  const r = await request(LOGIN, 'POST', COMMON, {
    login_method: 'phone', phone: j.phone || '', sms_code: j.code || '',
  }, 30000);
  const d = parseJson(r.text) || {};
  if (d.code !== 0) return { ok: false, msg: (d.msg || ('HTTP ' + r.status)) + hint(d.msg) };
  const data = d.data || {};
  const at = data.accessToken || data.access_token || '';
  const rt = data.refreshToken || data.refresh_token || '';
  if (!at || !rt) return { ok: false, msg: '响应缺少 accessToken / refreshToken' };

  let verifyMsg = '（本次未做 RT 验证）';
  if (j.verify) {
    try {
      const rr = await request(REFRESH, 'POST',
        Object.assign({ 'X-Refresh-Token': rt }, REFRESH_HEADERS), {}, 20000);
      const rd = parseJson(rr.text) || {};
      verifyMsg = rd.code === 0 && (rd.data || {}).accessToken
        ? '✅ RT 刷新验证通过（已换发新 AT）'
        : '⚠️ RT 刷新未通过：' + (rd.msg || ('HTTP ' + rr.status));
    } catch (e) {
      verifyMsg = '⚠️ RT 刷新异常：' + e.message;
    }
  }
  return { ok: true, phone: j.phone, at, rt, verifyMsg };
}

async function apiPanelPush(j) {
  const diag = [];
  const accounts = Array.isArray(j.accounts) ? j.accounts : [];
  if (!accounts.length) throw new Error('没有可推送的账号');

  const rawBase = String(j.base || '');
  let base;
  try {
    base = normalizeBase(rawBase);
  } catch (e) {
    diag.push({ step: 'normalize', ok: false, from: sanitize(rawBase), error: sanitize(e.message) });
    e.detail = diag;
    throw e;
  }
  diag.push({
    step: 'normalize', ok: true, from: sanitize(rawBase), to: sanitize(base),
    autoScheme: !/^https?:\/\//i.test(rawBase.trim()),
    note: '地址缺协议时自动补 http://（修复 no protocol 错误）',
  });

  const head = { 'Content-Type': 'application/json', 'X-Client-Type': 'web', 'X-Client-App': 'daidai-panel-web' };
  const asArray = (s) => {
    const x = parseJson(s);
    if (Array.isArray(x)) return x;
    return (x && (x.data || x.envs)) || [];
  };
  const rec = (o) => { diag.push(o); };

  try {
    const lr = await request(base + '/auth/login', 'POST', head, {
      username: j.user || '', password: j.pass || '', totp_code: '',
    }, 12000);
    const ld = parseJson(lr.text) || {};
    const token = ld.access_token || '';
    rec({ step: 'auth/login', method: 'POST', path: '/api/auth/login',
      status: lr.status, ms: lr.ms, hasToken: !!token,
      note: '响应体含令牌，仅记录是否取得令牌' });
    if (!token) {
      throw new Error('面板登录失败（账号密码错误或触发登录频控）：'
        + sanitize(String(lr.text)).slice(0, 100));
    }
    const auth = Object.assign({}, head, { Authorization: 'Bearer ' + token });

    const er = await request(base + '/envs', 'GET', auth, null, 12000);
    let envs = asArray(er.text);
    const cur = envs.find((e) => e && e.name === VAR);
    let lines = cur && cur.value
      ? String(cur.value).replace(/\r/g, '').split('\n').map((s) => s.trim()).filter(Boolean)
      : [];
    rec({ step: 'envs/read-1', method: 'GET', path: '/api/envs', status: er.status, ms: er.ms,
      envCount: envs.length, varFound: !!cur, lines: lines.length,
      note: '响应体含完整凭据，未记录内容' });

    const phoneOf = (l) => l.split(':')[0].trim();
    let added = 0, updated = 0;
    for (const a of accounts) {
      const line = a.phone + ':' + a.at + ':' + a.rt;
      const idx = lines.findIndex((l) => phoneOf(l) === a.phone);
      if (idx >= 0) { lines[idx] = line; updated++; } else { lines.push(line); added++; }
    }
    const value = lines.join('\n');
    const payload = cur
      ? Object.assign({}, cur, { name: VAR, value, remarks: cur.remarks || 'WorkBuddy 多账号 手机号:AT:RT，每行一个' })
      : { name: VAR, value, remarks: 'WorkBuddy 多账号 手机号:AT:RT，每行一个' };
    const wr = cur
      ? await request(base + '/envs/' + cur.id, 'PUT', auth, payload, 12000)
      : await request(base + '/envs', 'POST', auth, payload, 12000);
    rec({ step: 'envs/write', method: cur ? 'PUT' : 'POST',
      path: '/api/envs' + (cur ? '/' + cur.id : ''), status: wr.status, ms: wr.ms,
      lines: lines.length, added, updated, note: '请求体含完整凭据，未记录内容' });

    const rr = await request(base + '/envs', 'GET', auth, null, 12000);
    envs = asArray(rr.text);
    const got = envs.find((e) => e && e.name === VAR);
    const back = got ? String(got.value || '') : '';
    const ok = accounts.every((a) => back.includes(a.phone + ':' + a.at + ':' + a.rt));
    rec({ step: 'verify', ok, method: 'GET', path: '/api/envs', status: rr.status, ms: rr.ms,
      note: ok ? '回读与本地一致' : '回读不一致，请到面板手动核对' });

    return {
      ok, status: wr.status, total: lines.length, added, updated, diag,
      msg: '面板变量 ' + VAR + ' 账号数 ' + lines.length
        + '（新增 ' + added + ' / 更新 ' + updated + '）· 回读校验：' + (ok ? '一致' : '不一致'),
    };
  } catch (e) {
    rec({ step: 'error', name: e.name, message: sanitize(e.message),
      stack: sanitize(String(e.stack || '')).split('\n').slice(0, 8).join('\n') });
    e.detail = diag;
    throw e;
  }
}

// ---------------------------------------------------------------- HTTP 服务

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const p = u.pathname;

  if (p === '/' || p === '/index.html') {
    const file = path.join(__dirname, 'index.html');
    fs.readFile(file, (err, buf) => {
      if (err) { res.writeHead(404); res.end('index.html not found'); return; }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(buf);
    });
    return;
  }

  if (p === '/diag.js') {
    fs.readFile(path.join(__dirname, 'diag.js'), (err, buf) => {
      if (err) { res.writeHead(404); res.end('diag.js not found'); return; }
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(buf);
    });
    return;
  }

  if (!p.startsWith('/api/')) { res.writeHead(404); res.end('not found'); return; }

  let raw = '';
  try {
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 2 * 1024 * 1024) throw new Error('请求体过大');
    }
  } catch (e) {
    send(res, 400, { ok: false, msg: '读取请求体失败：' + e.message });
    return;
  }

  let j = {};
  try { j = raw ? JSON.parse(raw) : {}; } catch (e) {
    send(res, 400, { ok: false, msg: '请求体不是合法 JSON' });
    return;
  }

  try {
    let out;
    switch (p) {
      case '/api/send-sms': out = await apiSendSms(j); break;
      case '/api/login': out = await apiLogin(j); break;
      case '/api/panel/push': out = await apiPanelPush(j); break;
      default: send(res, 404, { ok: false, msg: '未知端点' }); return;
    }
    // 只记录方法/路径/结论/脱敏后的诊断链，绝不记录验证码、密码或 token
    console.log('[' + new Date().toTimeString().slice(0, 8) + ']', req.method, p, '=>', out.ok ? 'ok' : 'fail');
    fileLog(req.method + ' ' + p + ' → ' + (out.ok ? 'ok' : 'fail')
      + (out.msg ? ' / ' + out.msg : '')
      + (Array.isArray(out.diag) ? ' / ' + JSON.stringify(out.diag) : ''));
    send(res, 200, out);
  } catch (e) {
    const msg = sanitize(e.message);
    const detail = Array.isArray(e.detail) ? e.detail : [{
      step: 'error', name: e.name, message: msg,
      stack: sanitize(String(e.stack || '')).split('\n').slice(0, 8).join('\n'),
    }];
    console.log('[' + new Date().toTimeString().slice(0, 8) + ']', req.method, p,
      '=> error:', msg.slice(0, 80));
    fileLog(req.method + ' ' + p + ' → ERROR / ' + msg + ' / ' + JSON.stringify(detail));
    send(res, 200, { ok: false, msg, detail, diag: detail });
  }
});

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  WorkBuddy 凭据助手 · 本地代理已启动');
  console.log('  本机访问 : http://127.0.0.1:' + PORT);
  if (LAN) {
    for (const ip of lanIPs()) console.log('  手机访问 : http://' + ip + ':' + PORT + '  (同一 WiFi)');
    console.log('  ⚠️  已开放局域网：任何同网设备都能触发发码，用毕请 Ctrl+C 关闭');
  } else {
    console.log('  手机访问 : 需 WB_LAN=1 启动后，用上面打印的局域网地址');
  }
  console.log('  安全：请求体不落盘、不打印；仅写脱敏诊断日志到 web/logs/');
  console.log('');
});
