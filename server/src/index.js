// w.flashoneg.com: the Android app uploads its screen data, already locked on the phone with a key
// made from the user's password. A browser with the link + password opens it read-only, and gets
// a notification (Web Push) the moment a wallet SMS lands on the Android.
// The server never sees the password, the key or any number: it holds scrambled bytes, a hash of the
// phone's upload token, and a hash of the "proof" (made from the key) a viewer must show to download.
import { DurableObject } from 'cloudflare:workers';
import VIEW from './view.html';
import SW from './sw.txt';
import ICON from './icon.bin';

const MAX = 6 * 1024 * 1024;      // one upload, encrypted and gzipped
const CHUNK = 900 * 1024;         // under the 1 MB SQLite row limit
const ID = /^[A-Za-z0-9_-]{16}$/;
const TRIES = 10, LOCK_MS = 60 * 60 * 1000;   // wrong passwords: 10, then wait an hour
const MAX_SUBS = 10;
const PUSH_HOSTS = /^(web\.push\.apple\.com|fcm\.googleapis\.com|updates\.push\.services\.mozilla\.com|[a-z0-9.-]+\.notify\.windows\.com)$/;

const HEAD = '<meta name="robots" content="noindex,nofollow">' +
  '<meta name="referrer" content="no-referrer">' +
  '<meta name="apple-mobile-web-app-capable" content="yes">' +
  '<meta name="mobile-web-app-capable" content="yes">' +
  '<meta name="apple-mobile-web-app-title" content="محافظي">' +
  '<meta name="theme-color" content="#F4F5F2">' +
  '<link rel="apple-touch-icon" href="/icon.png">' +
  '<link rel="icon" href="/icon.png">' +
  '';

// The home-screen icon on the iPhone opens start_url only (no #, and its own storage, apart from Safari),
// so each link gets its own manifest that opens /v/<id>.<salt> (both public: the password is the secret).
const LINK = /^\/v\/([A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22})$/;
const MLINK = /^\/m\/([A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22})\.webmanifest$/;
const manifest = (start) => JSON.stringify({
  name: 'محافظي', short_name: 'محافظي', lang: 'ar', dir: 'rtl', id: start, start_url: start, scope: '/', display: 'standalone',
  background_color: '#F4F5F2', theme_color: '#F4F5F2',
  icons: [{ src: '/icon.png', sizes: '192x192', type: 'image/png' }]
});

const SECURITY = { 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY' };

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY } });
}
const enc = new TextEncoder();
const b64u = b => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64u = s => { s = s.replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '='; return Uint8Array.from(atob(s), c => c.charCodeAt(0)); };
const cat = (...a) => { const o = new Uint8Array(a.reduce((s, x) => s + x.length, 0)); let i = 0; for (const x of a) { o.set(x, i); i += x.length; } return o; };
async function sha(s) { return b64u(await crypto.subtle.digest('SHA-256', enc.encode(s))); }
function bearer(req) { const a = req.headers.get('Authorization') || ''; return a.startsWith('Bearer ') ? a.slice(7).trim() : ''; }

/* ---------- Web Push (RFC 8291 payload encryption + RFC 8292 VAPID) ---------- */
async function hkdf(salt, ikm, info, len) {
  const k = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, k, len * 8));
}
export async function encryptPush(payload, p256dh, auth) {
  const ua = unb64u(p256dh), secret = unb64u(auth);
  const eph = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const as = new Uint8Array(await crypto.subtle.exportKey('raw', eph.publicKey));
  const uaKey = await crypto.subtle.importKey('raw', ua, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: uaKey }, eph.privateKey, 256));
  const ikm = await hkdf(secret, shared, cat(enc.encode('WebPush: info\0'), ua, as), 32);
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const cek = await hkdf(salt, ikm, enc.encode('Content-Encoding: aes128gcm\0'), 16);
  const nonce = await hkdf(salt, ikm, enc.encode('Content-Encoding: nonce\0'), 12);
  const key = await crypto.subtle.importKey('raw', cek, 'AES-GCM', false, ['encrypt']);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, key, cat(payload, new Uint8Array([2]))));
  const head = new Uint8Array(21); head.set(salt); new DataView(head.buffer).setUint32(16, 4096); head[20] = 65;
  return cat(head, as, ct);
}
async function vapid(env, endpoint) {
  const pub = unb64u(env.VAPID_PUBLIC);
  const jwk = { kty: 'EC', crv: 'P-256', x: b64u(pub.slice(1, 33)), y: b64u(pub.slice(33, 65)), d: env.VAPID_PRIVATE, ext: true };
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const part = o => b64u(enc.encode(JSON.stringify(o)));
  const unsigned = part({ typ: 'JWT', alg: 'ES256' }) + '.' + part({ aud: new URL(endpoint).origin, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: 'https://w.flashoneg.com' });
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(unsigned));
  return `vapid t=${unsigned}.${b64u(sig)}, k=${env.VAPID_PUBLIC}`;
}
function pushAllowed(env, endpoint) {
  try {
    const u = new URL(endpoint);
    if (env.PUSH_TEST === '1' && u.hostname === '127.0.0.1') return true;
    return u.protocol === 'https:' && PUSH_HOSTS.test(u.hostname);
  } catch { return false; }
}

/** One box per link: the locked copy, who may replace it, who may read it, and the phones to notify. */
export class Box extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.env = env;
    this.sql = ctx.storage.sql;
    this.tables();
  }
  tables() {
    this.sql.exec('CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS part (n INTEGER PRIMARY KEY, b BLOB)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS sub (endpoint TEXT PRIMARY KEY, p256dh TEXT, auth TEXT, at INTEGER)');
  }
  meta(k) { const r = this.sql.exec('SELECT v FROM meta WHERE k=?', k).toArray()[0]; return r ? r.v : null; }
  setMeta(k, v) { this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', k, String(v)); }

  /** The viewer proves it has the password (proof = sha256(key + "sub")). Wrong tries are counted. */
  async viewerOk(req) {
    const want = this.meta('sub');
    if (!want) return { res: json({ error: 'gone' }, 404) };
    const now = Date.now(), from = +(this.meta('failFrom') || 0), fails = +(this.meta('fails') || 0);
    if (fails >= TRIES && now - from < LOCK_MS) return { res: json({ error: 'wait', wait: LOCK_MS - (now - from) }, 429) };
    if (await sha(req.headers.get('X-Sub') || '') === want) { if (fails) { this.setMeta('fails', 0); } return {}; }
    if (now - from >= LOCK_MS) { this.setMeta('failFrom', now); this.setMeta('fails', 1); } else this.setMeta('fails', fails + 1);
    return { res: json({ error: 'password' }, 403) };
  }

  async fetch(req) {
    const url = new URL(req.url);
    const sub = url.pathname.endsWith('/sub'), push = url.pathname.endsWith('/push');

    if (req.method === 'GET' || sub) {
      const v = await this.viewerOk(req);
      if (v.res) return v.res;
      if (sub) {
        if (req.method === 'POST') {
          const s = await req.json().catch(() => null);
          if (!s || typeof s.endpoint !== 'string' || !s.keys || !s.keys.p256dh || !s.keys.auth || !pushAllowed(this.env, s.endpoint)) return json({ error: 'bad' }, 400);
          this.sql.exec('INSERT OR REPLACE INTO sub (endpoint, p256dh, auth, at) VALUES (?, ?, ?, ?)', s.endpoint, s.keys.p256dh, s.keys.auth, Date.now());
          this.sql.exec('DELETE FROM sub WHERE endpoint NOT IN (SELECT endpoint FROM sub ORDER BY at DESC LIMIT ?)', MAX_SUBS);
          return json({ ok: true });
        }
        if (req.method === 'DELETE') {
          const s = await req.json().catch(() => ({}));
          this.sql.exec('DELETE FROM sub WHERE endpoint=?', String(s.endpoint || ''));
          return json({ ok: true });
        }
        return json({ error: 'method' }, 405);
      }
      const at = this.meta('at');
      if (url.searchParams.get('since') === at) return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store', 'X-At': at } });
      const parts = this.sql.exec('SELECT b FROM part ORDER BY n').toArray().map(r => new Uint8Array(r.b));
      return new Response(cat(...parts), { headers: { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store', 'X-At': at, ...SECURITY } });
    }

    // from here on: only the Android phone that made the link
    const tok = bearer(req);
    if (tok.length < 20) return json({ error: 'token' }, 401);
    const h = await sha(tok);
    const owner = this.meta('tok');
    if (owner && owner !== h) return json({ error: 'token' }, 403);

    if (req.method === 'DELETE') {
      if (owner) { await this.ctx.storage.deleteAll(); this.tables(); }
      return json({ ok: true });
    }

    if (push && req.method === 'POST') {
      if (!owner) return json({ error: 'gone' }, 404);
      const body = new Uint8Array(await req.arrayBuffer());
      if (body.length < 30 || body.length > 3000 || body[0] !== 2) return json({ error: 'bad' }, 400);
      const subs = this.sql.exec('SELECT endpoint, p256dh, auth FROM sub').toArray();
      let sent = 0; const codes = [];
      await Promise.all(subs.map(async s => {
        try {
          const r = await fetch(s.endpoint, { method: 'POST', body: await encryptPush(body, s.p256dh, s.auth), headers: {
            'Content-Encoding': 'aes128gcm', 'Content-Type': 'application/octet-stream', TTL: '86400', Urgency: 'high',
            Authorization: await vapid(this.env, s.endpoint) } });
          codes.push(r.status);
          if (r.status === 404 || r.status === 410) this.sql.exec('DELETE FROM sub WHERE endpoint=?', s.endpoint);
          else if (r.ok) sent++;
        } catch { codes.push(0); }
      }));
      // the log only: how the Android caught the SMS, how many seconds late, and what Apple/Google answered (no content)
      console.log('push', JSON.stringify({ via: req.headers.get('X-Via') || '', lag: +(req.headers.get('X-Lag') || 0), diag: req.headers.get('X-Diag') || '', subs: subs.length, sent, codes }));
      return json({ ok: true, sent });
    }

    if (req.method === 'PUT') {
      const body = new Uint8Array(await req.arrayBuffer());
      const proof = req.headers.get('X-Sub') || '';
      if (body.length < 30 || body.length > MAX || body[0] !== 1 || proof.length < 20) return json({ error: 'bad' }, 400);
      const at = String(Date.now()), ph = await sha(proof);
      this.ctx.storage.transactionSync(() => {
        this.sql.exec('DELETE FROM part');
        for (let i = 0, n = 0; i < body.length; i += CHUNK, n++) this.sql.exec('INSERT INTO part (n, b) VALUES (?, ?)', n, body.slice(i, i + CHUNK).buffer);
        if (!owner) this.setMeta('tok', h);
        this.setMeta('sub', ph);
        this.setMeta('at', at);
      });
      return json({ ok: true, at });
    }
    return json({ error: 'method' }, 405);
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname;

    const m = p.match(/^\/api\/s\/([^/]+)(\/sub|\/push)?$/);
    if (m) {
      if (!ID.test(m[1])) return json({ error: 'id' }, 404);
      return env.BOX.get(env.BOX.idFromName(m[1])).fetch(req);
    }
    if (p === '/api/key') return json({ key: env.VAPID_PUBLIC || null });
    if (p.startsWith('/api/')) return json({ error: 'not found' }, 404);

    if (p === '/sw.js') return new Response(SW, { headers: { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-cache', ...SECURITY } });
    if (p === '/icon.png') return new Response(ICON, { headers: { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' } });
    const ml = p.match(MLINK);
    if (p === '/manifest.webmanifest' || ml) return new Response(manifest(ml ? '/v/' + ml[1] : '/'), { headers: { 'Content-Type': 'application/manifest+json', 'Cache-Control': 'public, max-age=3600' } });

    const lk = p.match(LINK);
    if (p === '/' || p === '/index.html' || lk) {
      const page = await env.ASSETS.fetch(new Request(new URL('/', url), { method: 'GET' }));
      const mf = '<link rel="manifest" href="' + (lk ? '/m/' + lk[1] + '.webmanifest' : '/manifest.webmanifest') + '">';
      const res = new HTMLRewriter().on('head', { element(e) {
        e.prepend('<base href="/">', { html: true });          // fonts/… stay at the root under /v/…
        e.append(HEAD + mf + VIEW, { html: true });
      } }).transform(page);
      return new Response(res.body, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache', ...SECURITY } });
    }
    return env.ASSETS.fetch(req);
  }
};
