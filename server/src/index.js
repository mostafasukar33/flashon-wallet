// wallet.flashoneg.com: the Android app uploads its screen data (already encrypted on the phone),
// and any browser that has the link (id + key after #) downloads it and opens it read-only.
// The server only ever holds scrambled bytes plus a hash of the phone's upload token.
import { DurableObject } from 'cloudflare:workers';
import VIEW from './view.html';
import ICON from './icon.bin';

const MAX = 6 * 1024 * 1024;      // one upload, encrypted and gzipped
const CHUNK = 900 * 1024;         // under the 1 MB SQLite row limit
const ID = /^[A-Za-z0-9_-]{16}$/;

const HEAD = '<meta name="robots" content="noindex,nofollow">' +
  '<meta name="referrer" content="no-referrer">' +
  '<meta name="apple-mobile-web-app-capable" content="yes">' +
  '<meta name="mobile-web-app-capable" content="yes">' +
  '<meta name="apple-mobile-web-app-title" content="محافظي">' +
  '<meta name="theme-color" content="#F4F5F2">' +
  '<link rel="apple-touch-icon" href="/icon.png">' +
  '<link rel="icon" href="/icon.png">' +
  '<link rel="manifest" href="/manifest.webmanifest">';

const MANIFEST = JSON.stringify({
  name: 'محافظي', short_name: 'محافظي', lang: 'ar', dir: 'rtl', start_url: '/', display: 'standalone',
  background_color: '#F4F5F2', theme_color: '#F4F5F2',
  icons: [{ src: '/icon.png', sizes: '192x192', type: 'image/png' }]
});

const SECURITY = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY'
};

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...SECURITY } });
}

async function sha(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return btoa(String.fromCharCode(...new Uint8Array(d)));
}

function bearer(req) {
  const a = req.headers.get('Authorization') || '';
  return a.startsWith('Bearer ') ? a.slice(7).trim() : '';
}

/** One box per link: the encrypted copy, when it arrived, and who may replace it. */
export class Box extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.tables();
  }
  tables() {
    this.sql.exec('CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS part (n INTEGER PRIMARY KEY, b BLOB)');
  }
  meta(k) { const r = this.sql.exec('SELECT v FROM meta WHERE k=?', k).toArray()[0]; return r ? r.v : null; }
  setMeta(k, v) { this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', k, String(v)); }

  async fetch(req) {
    const url = new URL(req.url);
    if (req.method === 'GET') {
      const at = this.meta('at');
      if (!at) return json({ error: 'gone' }, 404);
      if (url.searchParams.get('since') === at) return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store', 'X-At': at } });
      const parts = this.sql.exec('SELECT b FROM part ORDER BY n').toArray().map(r => new Uint8Array(r.b));
      const len = parts.reduce((s, p) => s + p.length, 0);
      const out = new Uint8Array(len); let o = 0;
      for (const p of parts) { out.set(p, o); o += p.length; }
      return new Response(out, { headers: { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store', 'X-At': at, ...SECURITY } });
    }

    const tok = bearer(req);
    if (tok.length < 20) return json({ error: 'token' }, 401);
    const h = await sha(tok);
    const owner = this.meta('tok');
    // the first upload claims the link; after that only the same phone can change it
    if (owner && owner !== h) return json({ error: 'token' }, 403);

    if (req.method === 'DELETE') {
      if (!owner) return json({ ok: true });
      await this.ctx.storage.deleteAll();
      this.tables();
      return json({ ok: true });
    }

    if (req.method === 'PUT') {
      const body = new Uint8Array(await req.arrayBuffer());
      if (body.length < 30 || body.length > MAX || body[0] !== 1) return json({ error: 'bad' }, 400);
      const at = String(Date.now());
      this.ctx.storage.transactionSync(() => {
        this.sql.exec('DELETE FROM part');
        for (let i = 0, n = 0; i < body.length; i += CHUNK, n++) this.sql.exec('INSERT INTO part (n, b) VALUES (?, ?)', n, body.slice(i, i + CHUNK).buffer);
        if (!owner) this.setMeta('tok', h);
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

    const m = p.match(/^\/api\/s\/([^/]+)$/);
    if (m) {
      if (!ID.test(m[1])) return json({ error: 'id' }, 404);
      if (!['GET', 'PUT', 'DELETE'].includes(req.method)) return json({ error: 'method' }, 405);
      return env.BOX.get(env.BOX.idFromName(m[1])).fetch(req);
    }
    if (p.startsWith('/api/')) return json({ error: 'not found' }, 404);

    if (p === '/icon.png') return new Response(ICON, { headers: { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' } });
    if (p === '/manifest.webmanifest') return new Response(MANIFEST, { headers: { 'Content-Type': 'application/manifest+json', 'Cache-Control': 'public, max-age=3600' } });

    if (p === '/' || p === '/index.html') {
      const page = await env.ASSETS.fetch(new Request(new URL('/', url), { method: 'GET' }));
      const res = new HTMLRewriter()
        .on('head', { element(e) { e.append(HEAD + VIEW, { html: true }); } })
        .transform(page);
      return new Response(res.body, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache', ...SECURITY } });
    }
    return env.ASSETS.fetch(req);
  }
};
