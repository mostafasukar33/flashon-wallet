/* اختبار السيرفر على الجهاز: node server/test/api.js (بيشغّل wrangler dev لوحده)
   بيقفل الحساب زي Sync.kt بالظبط، ويرفعه، ويفتح اللينك في متصفح بمقاس آيفون ويتأكد إن الأرقام ظاهرة ومفيش تعديل. */
const {spawn} = require('child_process'), path = require('path'), crypto = require('crypto'), zlib = require('zlib');
const PORT = 8798, U = `http://127.0.0.1:${PORT}`;
let pass = 0, fail = 0;
const eq = (name, got, want) => { const ok = JSON.stringify(got) === JSON.stringify(want); ok ? pass++ : fail++; console.log((ok ? '  ✔ ' : '  ✖ ') + name + (ok ? '' : `\n     المتوقع: ${JSON.stringify(want)}\n     اللي طلع: ${JSON.stringify(got)}`)); };
const b64 = b => Buffer.from(b).toString('base64url');

function seal(keyB64, obj) {   // نفس Sync.kt: [1][iv 12][AES-GCM(gzip(json)) + tag]
  const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', Buffer.from(keyB64, 'base64url'), iv);
  const ct = Buffer.concat([c.update(zlib.gzipSync(Buffer.from(JSON.stringify(obj)))), c.final(), c.getAuthTag()]);
  return Buffer.concat([Buffer.from([1]), iv, ct]);
}
const sample = (balance) => ({ t: Date.now() - 120000, data: {
  version: '1.50', versionCode: 50, permission: true, txNotify: true, lines: {}, phoneLines: [], noLine: false,
  settings: {monthReceive: 200000, monthSend: 200000, daySend: 60000, dayReceiveOn: false, dayReceive: 60000, feesCount: true},
  months: ['2026-11'],
  now: [{w: 'vf', p: 'vf', own: '01000000000', label: 'فودافون كاش', hasData: true, balance, lastTime: Date.now(), monthReceived: 7000, monthSent: 500, daySent: 0, dayReceived: 0, receiveLeft: 193000, sendLeftMonth: 199500, sendLeftDay: 60000, receiveLeftDay: null, canSendNow: balance, locked: 0, limits: {monthReceive: 200000, monthSend: 200000, daySend: 60000, own: false}}],
  summaries: {'2026-11': {vf: {inTotal: 7000, outTotal: 500, fees: 0, count: 2, start: 0, end: balance, received: 7000, sent: 500}}},
  txs: [{id: 'a1', w: 'vf', p: 'vf', type: 'in', kind: 'استلام', amount: 7000, fee: 0, balance, phone: '01011112222', name: 'تجربة', smsName: 'تجربة', time: Date.now() - 3600000, how: 'words'}],
  unknown: [], bodies: {a1: 'رسالة تجربة: تم استلام مبلغ 7000 جنيه'}
}});

(async () => {
  const w = spawn('npx', ['wrangler', 'dev', '--port', String(PORT), '--ip', '127.0.0.1', '--persist-to', '/tmp/wallet-test-' + Date.now()], {cwd: path.join(__dirname, '..'), stdio: 'pipe', detached: true});
  let log = ''; w.stdout.on('data', d => log += d); w.stderr.on('data', d => log += d);
  try {
    for (let i = 0; i < 90 && !log.includes('Ready on'); i++) await new Promise(r => setTimeout(r, 500));
    if (!log.includes('Ready on')) throw new Error('wrangler مقامش:\n' + log);

    const id = b64(crypto.randomBytes(12)), key = b64(crypto.randomBytes(32)), tok = b64(crypto.randomBytes(24));
    const S = U + '/api/s/' + id, auth = t => ({authorization: 'Bearer ' + t});
    console.log('السيرفر');
    eq('لينك جديد مفيهوش حاجة', (await fetch(S)).status, 404);
    eq('رقم لينك غلط', (await fetch(U + '/api/s/abc')).status, 404);
    eq('رفع من غير مفتاح', (await fetch(S, {method: 'PUT', body: seal(key, sample(1))})).status, 401);
    const r1 = await fetch(S, {method: 'PUT', headers: auth(tok), body: seal(key, sample(8713.7))});
    eq('أول رفع بيحجز اللينك', r1.status, 200);
    eq('موبايل تاني ميقدرش يكتب فوقه', (await fetch(S, {method: 'PUT', headers: auth(b64(crypto.randomBytes(24))), body: seal(key, sample(1))})).status, 403);
    eq('ولا يمسحه', (await fetch(S, {method: 'DELETE', headers: auth(b64(crypto.randomBytes(24)))})).status, 403);
    const g = await fetch(S), atv = g.headers.get('x-at'), got = Buffer.from(await g.arrayBuffer());
    const d = crypto.createDecipheriv('aes-256-gcm', Buffer.from(key, 'base64url'), got.subarray(1, 13)); d.setAuthTag(got.subarray(got.length - 16));
    const back = JSON.parse(zlib.gunzipSync(Buffer.concat([d.update(got.subarray(13, got.length - 16)), d.final()])));
    eq('اللي نزل هو اللي اترفع', back.data.now[0].balance, 8713.7);
    eq('السيرفر شايف بايتات مقفولة بس', got.includes(Buffer.from('8713')), false);
    eq('لو مفيش جديد بيرد فاضي', (await fetch(S + '?since=' + atv)).status, 204);
    const big = Buffer.alloc(2_500_000); crypto.randomFillSync(big); big[0] = 1;
    eq('رفع كبير بيتقسم ويرجع زي ما هو', (await fetch(S, {method: 'PUT', headers: auth(tok), body: big})).status, 200);
    eq('… نفس البايتات', Buffer.from(await (await fetch(S)).arrayBuffer()).equals(big), true);
    await fetch(S, {method: 'PUT', headers: auth(tok), body: seal(key, sample(8713.7))});

    console.log('الصفحة');
    const page = await (await fetch(U + '/')).text();
    eq('الصفحة فيها وضع الاطلاع', page.includes('window.WalletView'), true);
    eq('وأيقونة للآيفون', page.includes('apple-touch-icon'), true);
    eq('الأيقونة موجودة', (await fetch(U + '/icon.png')).headers.get('content-type'), 'image/png');
    eq('الخطوط بتتحمل', (await fetch(U + '/fonts/IBMPlexSansArabic-Bold.ttf')).status, 200);

    const {chromium, devices} = require('playwright');
    const br = await chromium.launch();
    const ctx = await br.newContext({...devices['iPhone 13']});
    const pg = await ctx.newPage(); const errs = []; pg.on('pageerror', e => errs.push(String(e)));
    await pg.goto(U + '/#' + id + '.' + key);
    await pg.waitForFunction(() => document.body.innerText.includes('8,713.70'), null, {timeout: 15000}).catch(() => {});
    const txt = await pg.innerText('body');
    eq('الرصيد ظاهر على الآيفون', txt.includes('8,713.70'), true);
    eq('مكتوب آخر تحديث', /آخر تحديث من الموبايل/.test(txt), true);
    eq('مفيش زرار إعدادات', await pg.isVisible('[data-act=settings]'), false);
    await pg.click('[data-act=tx]');
    await pg.waitForTimeout(400);
    eq('نص الرسالة بيظهر', (await pg.innerText('#sheet')).includes('رسالة تجربة'), true);
    eq('مفيش أزرار تصحيح', await pg.locator('#sheet [data-act=settype]').count(), 0);
    await pg.screenshot({path: process.env.SHOT || '/tmp/wallet-view.png'});
    // تحديث من الموبايل بيوصل لوحده
    await fetch(S, {method: 'PUT', headers: auth(tok), body: seal(key, sample(9999))});
    await pg.evaluate(() => { document.getElementById('sheetBg').click(); App.reload(); });
    await pg.waitForFunction(() => document.body.innerText.includes('9,999'), null, {timeout: 8000}).catch(() => {});
    eq('الرقم الجديد وصل', (await pg.innerText('body')).includes('9,999'), true);
    // بعد ما يتقفل من التطبيق
    eq('قفل اللينك', (await fetch(S, {method: 'DELETE', headers: auth(tok)})).status, 200);
    eq('بعد القفل مفيش حاجة', (await fetch(S)).status, 404);
    await pg.evaluate(() => App.reload()); await pg.waitForTimeout(800);
    eq('الصفحة بتقول اللينك اتقفل', (await pg.innerText('#vnote')).includes('اتقفل'), true);
    // لينك ناقص
    const p2 = await (await br.newContext({...devices['iPhone 13']})).newPage();
    await p2.goto(U + '/'); await p2.waitForTimeout(500);
    eq('من غير لينك بيقول افتحه من التطبيق', (await p2.innerText('body')).includes('شوفه من موبايل تاني'), true);
    eq('مفيش أخطاء في الصفحة', errs, []);
    await br.close();
  } catch (e) { fail++; console.log('  ✖ ' + e.stack); }
  finally { try { process.kill(-w.pid); } catch (e) {} }
  console.log(`\n${pass} نجحت، ${fail} فشلت`);
  process.exit(fail ? 1 : 0);
})();
