/* اختبار السيرفر على الجهاز: node server/test/api.js (بيشغّل wrangler dev لوحده)
   بيقفل الحساب بالباسورد زي Sync.kt بالظبط ويرفعه، ويجرب الإشعارات على خدمة push تجريبية،
   ويفتح اللينك في متصفح بمقاس آيفون ويتأكد إن الباسورد مطلوب والأرقام ظاهرة ومفيش تعديل. */
const {spawn} = require('child_process'), path = require('path'), crypto = require('crypto'), zlib = require('zlib'), http = require('http');
const ece = require('http_ece');
const PORT = 8798, U = `http://127.0.0.1:${PORT}`, ROUNDS = 150000;
let pass = 0, fail = 0;
const eq = (name, got, want) => { const ok = JSON.stringify(got) === JSON.stringify(want); ok ? pass++ : fail++; console.log((ok ? '  ✔ ' : '  ✖ ') + name + (ok ? '' : `\n     المتوقع: ${JSON.stringify(want)}\n     اللي طلع: ${JSON.stringify(got)}`)); };
const b64 = b => Buffer.from(b).toString('base64url');

/* نفس Sync.kt */
const link = (pw) => { const id = b64(crypto.randomBytes(12)), salt = crypto.randomBytes(16), key = crypto.pbkdf2Sync(pw, salt, ROUNDS, 32, 'sha256');
  return {id, salt: b64(salt), key, proof: b64(crypto.createHash('sha256').update(Buffer.concat([key, Buffer.from('sub')])).digest()), tok: b64(crypto.randomBytes(24))}; };
function seal(key, version, buf) {
  const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv);
  return Buffer.concat([Buffer.from([version]), iv, c.update(buf), c.final(), c.getAuthTag()]);
}
function unseal(key, b) { const d = crypto.createDecipheriv('aes-256-gcm', key, b.subarray(1, 13)); d.setAuthTag(b.subarray(b.length - 16)); return Buffer.concat([d.update(b.subarray(13, b.length - 16)), d.final()]); }
const snap = (key, balance) => seal(key, 1, zlib.gzipSync(Buffer.from(JSON.stringify(sample(balance)))));
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
  // مفاتيح VAPID تجريبية
  const vk = crypto.generateKeyPairSync('ec', {namedCurve: 'prime256v1'}).privateKey.export({format: 'jwk'});
  const VPUB = b64(Buffer.concat([Buffer.from([4]), Buffer.from(vk.x, 'base64url'), Buffer.from(vk.y, 'base64url')]));
  // خدمة push تجريبية (مكان سيرفرات أبل)
  const got = []; let status = 201;
  const ps = http.createServer((q, r) => { const b = []; q.on('data', d => b.push(d)); q.on('end', () => { got.push({path: q.url, h: q.headers, body: Buffer.concat(b)}); r.writeHead(status); r.end(); }); });
  await new Promise(r => ps.listen(0, '127.0.0.1', r));
  const PS = `http://127.0.0.1:${ps.address().port}`;

  const w = spawn('npx', ['wrangler', 'dev', '--port', String(PORT), '--ip', '127.0.0.1', '--persist-to', '/tmp/wallet-test-' + Date.now(),
    '--var', 'VAPID_PUBLIC:' + VPUB, '--var', 'VAPID_PRIVATE:' + vk.d, '--var', 'PUSH_TEST:1'], {cwd: path.join(__dirname, '..'), stdio: 'pipe', detached: true});
  let log = ''; w.stdout.on('data', d => log += d); w.stderr.on('data', d => log += d);
  try {
    for (let i = 0; i < 90 && !log.includes('Ready on'); i++) await new Promise(r => setTimeout(r, 500));
    if (!log.includes('Ready on')) throw new Error('wrangler مقامش:\n' + log);

    const L = link('secret123');
    const S = U + '/api/s/' + L.id, phone = (t = L.tok) => ({authorization: 'Bearer ' + t, 'x-sub': L.proof});
    const viewer = (p = L.proof) => ({'x-sub': p});
    console.log('الرفع والباسورد');
    eq('لينك جديد مفيهوش حاجة', (await fetch(S, {headers: viewer()})).status, 404);
    eq('رقم لينك غلط', (await fetch(U + '/api/s/abc')).status, 404);
    eq('رفع من غير مفتاح الموبايل', (await fetch(S, {method: 'PUT', body: snap(L.key, 1)})).status, 401);
    eq('أول رفع بيحجز اللينك', (await fetch(S, {method: 'PUT', headers: phone(), body: snap(L.key, 8713.7)})).status, 200);
    eq('موبايل تاني ميقدرش يكتب فوقه', (await fetch(S, {method: 'PUT', headers: phone(b64(crypto.randomBytes(24))), body: snap(L.key, 1)})).status, 403);
    eq('ولا يمسحه', (await fetch(S, {method: 'DELETE', headers: phone(b64(crypto.randomBytes(24)))})).status, 403);
    eq('اللينك لوحده مبيفتحش حاجة', (await fetch(S)).status, 403);
    const wrong = link('wrongpass'); wrong.salt = L.salt;
    const wrongProof = b64(crypto.createHash('sha256').update(Buffer.concat([crypto.pbkdf2Sync('wrongpass', Buffer.from(L.salt, 'base64url'), ROUNDS, 32, 'sha256'), Buffer.from('sub')])).digest());
    eq('باسورد غلط مبيفتحش', (await fetch(S, {headers: viewer(wrongProof)})).status, 403);
    const g = await fetch(S, {headers: viewer()}), atv = g.headers.get('x-at'), blob = Buffer.from(await g.arrayBuffer());
    eq('الباسورد الصح بيفتح', g.status, 200);
    eq('اللي نزل هو اللي اترفع', JSON.parse(zlib.gunzipSync(unseal(L.key, blob))).data.now[0].balance, 8713.7);
    eq('السيرفر شايف بايتات مقفولة بس', blob.includes(Buffer.from('8713')), false);
    eq('لو مفيش جديد بيرد فاضي', (await fetch(S + '?since=' + atv, {headers: viewer()})).status, 204);
    const big = Buffer.alloc(2_500_000); crypto.randomFillSync(big); big[0] = 1;
    eq('رفع كبير بيتقسم ويرجع زي ما هو', (await fetch(S, {method: 'PUT', headers: phone(), body: big})).status, 200);
    eq('… نفس البايتات', Buffer.from(await (await fetch(S, {headers: viewer()})).arrayBuffer()).equals(big), true);
    await fetch(S, {method: 'PUT', headers: phone(), body: snap(L.key, 8713.7)});
    // تخمين الباسورد: 10 محاولات وبعدين يستنى ساعة
    const L2 = link('another1'); await fetch(U + '/api/s/' + L2.id, {method: 'PUT', headers: {authorization: 'Bearer ' + L2.tok, 'x-sub': L2.proof}, body: snap(L2.key, 1)});
    for (let i = 0; i < 10; i++) await fetch(U + '/api/s/' + L2.id, {headers: viewer('guess' + i + 'xxxxxxxxxxxxxxxxxxxxxxxx')});
    eq('بعد 10 غلط بيقفل', (await fetch(U + '/api/s/' + L2.id, {headers: viewer(L2.proof)})).status, 429);
    eq('واللينك التاني مش متأثر', (await fetch(S, {headers: viewer()})).status, 200);

    console.log('الإشعارات');
    const ua = crypto.createECDH('prime256v1'); ua.generateKeys(); const auth = crypto.randomBytes(16);
    const subj = {endpoint: PS + '/push/one', keys: {p256dh: b64(ua.getPublicKey()), auth: b64(auth)}};
    eq('الاشتراك من غير باسورد مرفوض', (await fetch(S + '/sub', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(subj)})).status, 403);
    eq('سيرفر push غريب مرفوض', (await fetch(S + '/sub', {method: 'POST', headers: {...viewer(), 'content-type': 'application/json'}, body: JSON.stringify({...subj, endpoint: 'https://evil.example.com/x'})})).status, 400);
    eq('الآيفون بيشترك', (await fetch(S + '/sub', {method: 'POST', headers: {...viewer(), 'content-type': 'application/json'}, body: JSON.stringify(subj)})).status, 200);
    eq('مفتاح الإشعارات ظاهر للصفحة', (await (await fetch(U + '/api/key')).json()).key, VPUB);
    const msgs = [{title: '+7,000 ج من تجربة', body: 'فودافون كاش · الرصيد 8,713.70', tag: 'a1'}];
    eq('حد غريب ميقدرش يبعت إشعار', (await fetch(S + '/push', {method: 'POST', headers: phone(b64(crypto.randomBytes(24))), body: seal(L.key, 2, Buffer.from(JSON.stringify(msgs)))})).status, 403);
    const pr = await (await fetch(S + '/push', {method: 'POST', headers: phone(), body: seal(L.key, 2, Buffer.from(JSON.stringify(msgs)))})).json();
    eq('الموبايل بيبعت إشعار', pr.sent, 1);
    const one = got[got.length - 1];
    eq('بيوصل لخدمة الـ push', one && one.path, '/push/one');
    eq('مقفول بطريقة aes128gcm', one.h['content-encoding'], 'aes128gcm');
    const inner = ece.decrypt(one.body, {version: 'aes128gcm', privateKey: ua, authSecret: b64(auth)});
    eq('خدمة الـ push مش شايفة نص الإشعار', one.body.includes(Buffer.from('7,000')) || inner.includes(Buffer.from('7,000')), false);
    eq('الآيفون بيفتحه بالمفتاح', JSON.parse(unseal(L.key, inner))[0].title, '+7,000 ج من تجربة');
    const m = /^vapid t=([^.]+)\.([^.]+)\.([^,]+), k=(.+)$/.exec(one.h.authorization || '');
    const claims = m && JSON.parse(Buffer.from(m[2], 'base64url'));
    eq('توقيع VAPID سليم', !!m && crypto.verify('sha256', Buffer.from(m[1] + '.' + m[2]), {key: crypto.createPublicKey({key: {kty: 'EC', crv: 'P-256', x: vk.x, y: vk.y}, format: 'jwk'}), dsaEncoding: 'ieee-p1363'}, Buffer.from(m[3], 'base64url')), true);
    eq('… لنفس الخدمة', claims && claims.aud, PS);
    status = 410;
    await fetch(S + '/push', {method: 'POST', headers: phone(), body: seal(L.key, 2, Buffer.from(JSON.stringify(msgs)))});
    status = 201;
    eq('اشتراك ميت بيتشال لوحده', (await (await fetch(S + '/push', {method: 'POST', headers: phone(), body: seal(L.key, 2, Buffer.from(JSON.stringify(msgs)))})).json()).sent, 0);
    await fetch(S + '/sub', {method: 'POST', headers: {...viewer(), 'content-type': 'application/json'}, body: JSON.stringify(subj)});

    console.log('الصفحة');
    const page = await (await fetch(U + '/')).text();
    eq('الصفحة فيها وضع الاطلاع', page.includes('window.WalletView'), true);
    eq('وأيقونة للآيفون', page.includes('apple-touch-icon'), true);
    eq('الأيقونة موجودة', (await fetch(U + '/icon.png')).headers.get('content-type'), 'image/png');
    eq('ملف الإشعارات موجود', (await fetch(U + '/sw.js')).headers.get('content-type'), 'text/javascript; charset=utf-8');
    eq('الخطوط بتتحمل', (await fetch(U + '/fonts/IBMPlexSansArabic-Bold.ttf')).status, 200);

    const {chromium, devices} = require('playwright');
    const br = await chromium.launch();
    const ctx = await br.newContext({...devices['iPhone 13']});
    const pg = await ctx.newPage(); const errs = []; pg.on('pageerror', e => errs.push(String(e)));
    const fonts = []; pg.on('response', r => { if (r.url().includes('/fonts/')) fonts.push(r.status()); });
    await pg.goto(U + '/#' + L.id + '.' + L.salt);   // اللينك القديم بـ # بيتحول لوحده
    await pg.waitForSelector('#vpwi');
    eq('اللينك بقى في العنوان نفسه', new URL(pg.url()).pathname, '/v/' + L.id + '.' + L.salt);
    const mhref = await pg.getAttribute('link[rel=manifest]', 'href');
    eq('ملف الأيقونة خاص باللينك ده', mhref, '/m/' + L.id + '.' + L.salt + '.webmanifest');
    eq('أيقونة الشاشة الرئيسية بتفتح نفس اللينك', (await (await fetch(U + mhref)).json()).start_url, '/v/' + L.id + '.' + L.salt);
    await pg.waitForTimeout(300);
    eq('الخطوط بتتحمل جوه /v/', fonts.length > 0 && fonts.every(x => x === 200), true);
    eq('بيطلب الباسورد الأول', (await pg.innerText('body')).includes('8,713.70'), false);
    await pg.fill('#vpwi', 'wrongpass'); await pg.click('#vpwb');
    await pg.waitForFunction(() => document.getElementById('vpwm') && document.getElementById('vpwm').textContent, null, {timeout: 15000});
    eq('باسورد غلط بيقول غلط', await pg.innerText('#vpwm'), 'الباسورد غلط');
    await pg.screenshot({path: (process.env.SHOT || '/tmp/wallet') + '-pw.png'});
    await pg.fill('#vpwi', 'secret123'); await pg.click('#vpwb');
    await pg.waitForFunction(() => document.body.innerText.includes('8,713.70'), null, {timeout: 15000}).catch(() => {});
    const txt = await pg.innerText('body');
    eq('الرصيد ظاهر على الآيفون', txt.includes('8,713.70'), true);
    eq('مكتوب آخر تحديث', /آخر تحديث من الموبايل/.test(txt), true);
    eq('مفيش زرار إعدادات', await pg.isVisible('[data-act=settings]'), false);
    eq('الباسورد نفسه مش محفوظ', await pg.evaluate(() => JSON.stringify(localStorage).includes('secret123')), false);
    // زي أيقونة الآيفون: بتفتح /v/… في مكان تخزين لوحده، فبيطلب الباسورد مرة واحدة ويفتح
    const p3 = await (await br.newContext({...devices['iPhone 13']})).newPage();
    await p3.goto(U + '/v/' + L.id + '.' + L.salt); await p3.waitForSelector('#vpwi');
    await p3.fill('#vpwi', 'secret123'); await p3.click('#vpwb');
    await p3.waitForFunction(() => document.body.innerText.includes('8,713.70'), null, {timeout: 15000}).catch(() => {});
    eq('من الأيقونة بيفتح بالباسورد', (await p3.innerText('body')).includes('8,713.70'), true);
    await pg.reload(); await pg.waitForFunction(() => document.body.innerText.includes('8,713.70'), null, {timeout: 15000}).catch(() => {});
    eq('المرة التانية بيفتح من غير باسورد', (await pg.innerText('body')).includes('8,713.70'), true);
    await pg.click('[data-act=tx]'); await pg.waitForTimeout(400);
    eq('نص الرسالة بيظهر', (await pg.innerText('#sheet')).includes('رسالة تجربة'), true);
    eq('مفيش أزرار تصحيح', await pg.locator('#sheet [data-act=settype]').count(), 0);
    await pg.evaluate(() => document.getElementById('sheetBg').click());
    // ملف الإشعارات بيفتح الإشعار بالمفتاح المحفوظ على الآيفون
    let sw = ctx.serviceWorkers()[0]; for (let i = 0; !sw && i < 20; i++) { await pg.waitForTimeout(250); sw = ctx.serviceWorkers()[0]; }
    const sealedPush = [...seal(L.key, 2, Buffer.from(JSON.stringify(msgs)))];
    const opened = sw && await sw.evaluate(a => unlock(new Uint8Array(a).buffer).then(x => x[0].title), sealedPush);
    eq('الإشعار بيتفتح على الآيفون', opened, '+7,000 ج من تجربة');
    await pg.screenshot({path: (process.env.SHOT || '/tmp/wallet') + '-view.png'});
    // تحديث من الموبايل بيوصل لوحده
    await fetch(S, {method: 'PUT', headers: phone(), body: snap(L.key, 9999)});
    await pg.evaluate(() => App.reload());
    await pg.waitForFunction(() => document.body.innerText.includes('9,999'), null, {timeout: 8000}).catch(() => {});
    eq('الرقم الجديد وصل', (await pg.innerText('body')).includes('9,999'), true);
    eq('قفل اللينك', (await fetch(S, {method: 'DELETE', headers: phone()})).status, 200);
    eq('بعد القفل مفيش حاجة', (await fetch(S, {headers: viewer()})).status, 404);
    await pg.evaluate(() => App.reload()); await pg.waitForTimeout(800);
    eq('الصفحة بتقول اللينك اتقفل', (await pg.innerText('#vnote')).includes('اتقفل'), true);
    const p2 = await (await br.newContext({...devices['iPhone 13']})).newPage();
    await p2.goto(U + '/'); await p2.waitForTimeout(500);
    eq('من غير لينك بيقول افتحه من التطبيق', (await p2.innerText('body')).includes('شوفه من موبايل تاني'), true);
    eq('مفيش أخطاء في الصفحة', errs, []);
    await br.close();
  } catch (e) { fail++; console.log('  ✖ ' + e.stack); }
  finally { try { process.kill(-w.pid); } catch (e) {} ps.close(); }
  console.log(`\n${pass} نجحت، ${fail} فشلت`);
  process.exit(fail ? 1 : 0);
})();
