// Fire TV remote relay - links the "Fire TV Help" app on a Fire TV with Wayne's web remote.
// The TV app connects out to this server (works through any home router), shows a 6-digit code,
// and only streams/accepts control after the person at the TV presses "Allow".
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8790);
const PASSWORD = process.env.ADMIN_PASSWORD || '';
const SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString('hex');
const APK = process.env.APK_PATH || '/data/fireremote.apk';
const PUB = path.join(__dirname, 'public');
const SESSION_DAYS = 30;

if (!PASSWORD || PASSWORD.length < 8) {
  console.error('ADMIN_PASSWORD must be set (8+ characters). Refusing to start.');
  process.exit(1);
}

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------- login sessions (signed cookie, no database) ----------
const hmac = (v) => crypto.createHmac('sha256', SECRET).update(v).digest('hex');
const makeSession = () => { const exp = String(Date.now() + SESSION_DAYS * 864e5); return exp + '.' + hmac(exp); };
function validSession(tok) {
  if (!tok || typeof tok !== 'string') return false;
  const i = tok.indexOf('.'); if (i < 1) return false;
  const exp = tok.slice(0, i), sig = tok.slice(i + 1), want = hmac(exp);
  if (sig.length !== want.length) return false;
  if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return false;
  return Number(exp) > Date.now();
}
function cookies(req) {
  const out = {};
  (req.headers.cookie || '').split(';').forEach((p) => { const i = p.indexOf('='); if (i > 0) out[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim()); });
  return out;
}
const authed = (req) => validSession(cookies(req).fr_session);
const passwordOk = (p) => {
  const a = Buffer.from(hmac(String(p || ''))), b = Buffer.from(hmac(PASSWORD));
  return crypto.timingSafeEqual(a, b);
};

// simple brute-force protection: 5 failed logins per IP per 15 minutes
const fails = new Map();
const clientIp = (req) => (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
function tooManyFails(ip) {
  const f = fails.get(ip); if (!f) return false;
  if (Date.now() - f.first > 15 * 60e3) { fails.delete(ip); return false; }
  return f.count >= 5;
}
function addFail(ip) { const f = fails.get(ip) || { count: 0, first: Date.now() }; f.count++; fails.set(ip, f); }

// ---------- devices and sessions ----------
const devices = new Map(); // code -> { ws, name, model, version, viewer, since }
const pushes = new Map();  // token -> { buf, name, code, exp }  (APKs queued for a stick to install)
const PUBLIC_URL = (process.env.PUBLIC_URL || 'https://xxxxx').replace(/\/+$/, '');
function newCode() {
  for (;;) { const c = String(crypto.randomInt(100000, 1000000)); if (!devices.has(c)) return c; }
}
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const deviceList = () => [...devices.entries()].map(([code, d]) => ({ code, name: d.name, model: d.model, version: d.version, busy: !!d.viewer, since: d.since }));

// ---------- HTTP ----------
function serveFile(res, file, type, extraHeaders) {
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end('Not found'); }
    res.writeHead(200, Object.assign({ 'Content-Type': type, 'Content-Length': buf.length, 'Cache-Control': 'no-store' }, extraHeaders || {}));
    res.end(buf);
  });
}
const SEC_HEADERS = { 'X-Frame-Options': 'DENY', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' };

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  Object.entries(SEC_HEADERS).forEach(([k, v]) => res.setHeader(k, v));

  if (p === '/health') { res.writeHead(200, { 'Content-Type': 'text/plain' }); return res.end('ok'); }

  // public: the Fire TV app download (for the Downloader app)
  if (p === '/tv' || p === '/tv/' || p === '/app.apk' || p === '/tv.apk') {
    return serveFile(res, APK, 'application/vnd.android.package-archive', { 'Content-Disposition': 'attachment; filename="HelpTool.apk"' });
  }

  // token-protected: a pushed APK for a stick to download and install (the token is the secret)
  if (p.startsWith('/push/') && req.method === 'GET') {
    const f = pushes.get(p.slice(6));
    if (!f || Date.now() > f.exp) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end('expired'); }
    res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': f.buf.length, 'Cache-Control': 'no-store' });
    return res.end(f.buf);
  }

  if (p === '/login' && req.method === 'GET') return serveFile(res, path.join(PUB, 'login.html'), 'text/html; charset=utf-8');
  if (p === '/login' && req.method === 'POST') {
    const ip = clientIp(req);
    if (tooManyFails(ip)) { res.writeHead(429, { 'Content-Type': 'text/plain' }); return res.end('Too many attempts. Try again in 15 minutes.'); }
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 4096) req.destroy(); });
    req.on('end', () => {
      const pw = new URLSearchParams(body).get('password');
      if (passwordOk(pw)) {
        fails.delete(ip);
        const isHttps = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https';
        const secure = isHttps ? ' Secure;' : '';
        res.writeHead(303, { 'Set-Cookie': `fr_session=${makeSession()}; Path=/; HttpOnly;${secure} SameSite=Lax; Max-Age=${SESSION_DAYS * 86400}`, Location: '/' });
        log('login ok from', ip, isHttps ? '(https)' : '(http/lan)');
      } else {
        addFail(ip); log('login FAILED from', ip);
        res.writeHead(303, { Location: '/login?bad=1' });
      }
      res.end();
    });
    return;
  }
  if (p === '/logout') { res.writeHead(303, { 'Set-Cookie': 'fr_session=; Path=/; Max-Age=0', Location: '/login' }); return res.end(); }

  if (!authed(req)) { res.writeHead(303, { Location: '/login' }); return res.end(); }

  if (p === '/' || p === '/index.html') return serveFile(res, path.join(PUB, 'index.html'), 'text/html; charset=utf-8');
  if (p === '/api/devices') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(deviceList())); }

  // authed: upload an APK and hand the connected stick a one-time install URL
  if (p === '/api/push' && req.method === 'POST') {
    const code = (url.searchParams.get('code') || '').replace(/\D/g, '');
    const name = String(req.headers['x-filename'] || 'update.apk').replace(/[^\w.\- ]/g, '_').slice(0, 60);
    const d = devices.get(code);
    if (!d) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end('That TV is not connected.'); }
    if (!d.viewer) { res.writeHead(409, { 'Content-Type': 'text/plain' }); return res.end('Start a session first (they must tap Allow) before sending an app.'); }
    const chunks = []; let size = 0; let tooBig = false;
    req.on('data', (c) => { size += c.length; if (size > 80 * 1024 * 1024) { tooBig = true; req.destroy(); return; } chunks.push(c); });
    req.on('end', () => {
      if (tooBig) { res.writeHead(413, { 'Content-Type': 'text/plain' }); return res.end('File too big (max 80 MB).'); }
      const buf = Buffer.concat(chunks);
      if (!buf.length) { res.writeHead(400, { 'Content-Type': 'text/plain' }); return res.end('Empty file.'); }
      const tok = crypto.randomBytes(18).toString('hex');
      pushes.set(tok, { buf, name, code, exp: Date.now() + 5 * 60e3 });
      send(d.ws, { type: 'install', url: `${PUBLIC_URL}/push/${tok}`, name, size: buf.length });
      log('push queued', name, buf.length, 'bytes for', code);
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, size: buf.length, name }));
    });
    req.on('error', () => {});
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('Not found');
});

// ---------- WebSockets ----------
const wssDevice = new WebSocketServer({ noServer: true, maxPayload: 2 * 1024 * 1024 });
const wssViewer = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

server.on('upgrade', (req, sock, head) => {
  const p = new URL(req.url, 'http://x').pathname;
  if (p === '/ws/device') return wssDevice.handleUpgrade(req, sock, head, (ws) => onDevice(ws, req));
  if (p === '/ws/viewer') {
    if (!authed(req)) { sock.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return sock.destroy(); }
    return wssViewer.handleUpgrade(req, sock, head, (ws) => onViewer(ws, req));
  }
  sock.destroy();
});

function endSession(d, reason) {
  if (!d || !d.viewer) return;
  const v = d.viewer; d.viewer = null;
  if (v.device === d) v.device = null;
  send(v, { type: 'ended', reason });
  send(d.ws, { type: 'ended', reason });
}

function onDevice(ws, req) {
  const code = newCode();
  const d = { ws, name: 'Fire TV', model: '', version: '', viewer: null, since: Date.now() };
  devices.set(code, d);
  ws.isAlive = true; ws.on('pong', () => { ws.isAlive = true; });
  send(ws, { type: 'code', code });
  log('device connected', code, clientIp(req));

  ws.on('message', (data, isBinary) => {
    if (isBinary) { // screen frame -> viewer
      if (d.viewer && d.viewer.readyState === 1 && d.viewer.bufferedAmount < 1.5 * 1024 * 1024) d.viewer.send(data, { binary: true });
      return;
    }
    let m; try { m = JSON.parse(data.toString()); } catch { return; }
    if (m.type === 'hello') {
      d.name = String(m.name || 'Fire TV').slice(0, 60); d.model = String(m.model || '').slice(0, 60); d.version = String(m.version || '').slice(0, 20);
      log('device', code, 'is', d.name, d.model, d.version);
    } else if (d.viewer && ['accepted', 'denied', 'info', 'status', 'adb', 'install-result', 'apps', 'app-action-result', 'app-batch-result'].includes(m.type)) {
      send(d.viewer, m);
      if (m.type === 'denied') endSession(d, 'The person at the TV said no.');
    } else if (m.type === 'stop') {
      endSession(d, 'The person at the TV stopped the session.');
    }
  });
  ws.on('close', () => { endSession(d, 'The TV disconnected.'); devices.delete(code); log('device gone', code); });
  ws.on('error', () => {});
}

function onViewer(ws, req) {
  ws.device = null;
  ws.isAlive = true; ws.on('pong', () => { ws.isAlive = true; });
  log('viewer connected', clientIp(req));
  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    let m; try { m = JSON.parse(data.toString()); } catch { return; }
    if (m.type === 'join') {
      const code = String(m.code || '').replace(/\D/g, '');
      const d = devices.get(code);
      if (!d) return send(ws, { type: 'error', message: 'No device with that code is online. Ask them to open Help Tool.' });
      if (d.viewer && d.viewer !== ws) return send(ws, { type: 'error', message: 'That device is already in a session.' });
      if (ws.device && ws.device !== d) endSession(ws.device, 'Remote switched to another device.');
      d.viewer = ws; ws.device = d;
      send(ws, { type: 'waiting', name: d.name, code });
      send(d.ws, { type: 'request', from: 'A helper' });
      log('session requested for', code, d.name);
    } else if (m.type === 'end') {
      if (ws.device) endSession(ws.device, 'The session was ended.');
    } else if (ws.device && ['key', 'text', 'quality', 'apps-list', 'app-action', 'app-batch'].includes(m.type)) {
      send(ws.device.ws, m);
    }
  });
  ws.on('close', () => { if (ws.device) endSession(ws.device, 'The remote was closed.'); });
  ws.on('error', () => {});
}

// keepalive: drop dead connections so codes don't linger
setInterval(() => {
  for (const wss of [wssDevice, wssViewer]) wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false; try { ws.ping(); } catch {}
  });
  const now = Date.now();
  for (const [tok, f] of pushes) if (now > f.exp) pushes.delete(tok);
}, 20000);

server.listen(PORT, () => log('relay listening on', PORT));
