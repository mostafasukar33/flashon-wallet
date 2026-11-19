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
  unknown: [], bodies: {a1: 'رسالة تجربة: تم استلام مبلغ 7000 جنيه'},
  xfer: {ready: true, remote: true, busy: false, accounts: [{w: 'vfs3', label: 'فودافون كاش', own: '01000000000', can: balance, bal: balance}], list: []}
}});

/* طلب التحويل من الآيفون: نفس view.html (نسخة 3، ومربوط بالاستخدام ده) */
const AAD = Buffer.from('flashon-cmd-v3');
function sealCmd(key, obj) { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); c.setAAD(AAD); return Buffer.concat([Buffer.from([3]), iv, c.update(JSON.stringify(obj)), c.final(), c.getAuthTag()]); }
function openCmd(key, b) { const d = crypto.createDecipheriv('aes-256-gcm', key, b.subarray(1, 13)); d.setAAD(AAD); d.setAuthTag(b.subarray(b.length - 16)); return JSON.parse(Buffer.concat([d.update(b.subarray(13, b.length - 16)), d.final()])); }

/* الخط الهادي بتاع الأندرويد (WebSocket بتوكن الموبايل، زي Ws.kt) */
function wsOpen(port, path, tok) {
  return new Promise((ok, no) => {
    const req = http.request({host: '127.0.0.1', port, path, headers: {Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'), 'Sec-WebSocket-Version': '13', Authorization: 'Bearer ' + tok}});
    req.on('response', r => no(new Error('status ' + r.statusCode)));
    req.on('upgrade', (res, sock, head) => {
      let buf = head || Buffer.alloc(0); const q = [], waiters = [];
      sock.on('data', d => {
        buf = Buffer.concat([buf, d]);
        for (;;) {
          if (buf.length < 2) break;
          let len = buf[1] & 127, off = 2;
          if (len === 126) { if (buf.length < 4) break; len = buf.readUInt16BE(2); off = 4; }
          if (buf.length < off + len) break;
          const op = buf[0] & 15, text = buf.subarray(off, off + len).toString(); buf = buf.subarray(off + len);
          if (op === 1) { const w = waiters.shift(); w ? w(text) : q.push(text); }
        }
      });
      const send = t => { const b = Buffer.from(t), m = crypto.randomBytes(4); sock.write(Buffer.concat([Buffer.from([0x81, 0x80 | b.length]), m, Buffer.from(b.map((x, i) => x ^ m[i % 4]))])); };
      ok({send, next: () => new Promise((r, j) => { if (q.length) return r(q.shift()); waiters.push(r); setTimeout(() => j(new Error('مفيش رد')), 5000); }),
        close: () => { try { sock.write(Buffer.from([0x88, 0x80, 1, 2, 3, 4])); } catch (e) {} sock.end(); }});
    });
    req.on('error', no); req.end();
  });
}

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

    console.log('التحويل من الآيفون');
    const C = S + '/cmd';
    const cmd1 = {k: 'xfer', id: 'abcdef123456', t: Date.now(), to: '01012345678', a: '500', pin: '123456', w: 'vfs3'};
    eq('طلب من غير باسورد مرفوض', (await fetch(C, {method: 'POST', body: sealCmd(L.key, cmd1)})).status, 403);
    eq('طلب بشكل غلط مرفوض', (await fetch(C, {method: 'POST', headers: viewer(), body: seal(L.key, 2, Buffer.from('[]'))})).status, 400);
    eq('الخط بتوكن غلط مرفوض', await wsOpen(PORT, '/api/s/' + L.id + '/ws', b64(crypto.randomBytes(24))).then(() => 'opened', e => e.message), 'status 403');
    eq('الخط من غير WebSocket مرفوض', (await fetch(S + '/ws', {headers: phone()})).status, 426);
    const ws = await wsOpen(PORT, '/api/s/' + L.id + '/ws', L.tok);
    ws.send('ping'); eq('السيرفر بيرد على البينج', await ws.next(), 'pong');
    const pr2 = await (await fetch(C, {method: 'POST', headers: viewer(), body: sealCmd(L.key, cmd1)})).json();
    eq('الطلب اتقبل والأندرويد متصل', pr2.live, true);
    eq('الأندرويد سمع "cmd" في نفس اللحظة', await ws.next().catch(e => e.message), 'cmd');
    eq('الآيفون ميقدرش يقرا الطلبات', (await fetch(C, {headers: viewer()})).status, 401);
    eq('موبايل تاني ميقدرش يقرا الطلبات', (await fetch(C, {headers: phone(b64(crypto.randomBytes(24)))})).status, 403);
    const lst = await (await fetch(C, {headers: phone()})).json();
    eq('الأندرويد بيجيب الطلب', lst.cmds.length, 1);
    const raw1 = Buffer.from(lst.cmds[0].b, 'base64url'), got1 = openCmd(L.key, raw1);
    eq('… ويفتحه بالمفتاح', got1.to + '/' + got1.a + '/' + got1.pin, '01012345678/500/123456');
    eq('السيرفر شايف بايتات مقفولة بس', raw1.includes(Buffer.from('01012345678')) || raw1.includes(Buffer.from('123456')), false);
    eq('الأندرويد بيمسحه بعد ما يشوفه', (await fetch(C + '?upto=' + lst.cmds[0].n, {method: 'DELETE', headers: phone()})).status, 200);
    eq('… ومفيش حاجة تاني', (await (await fetch(C, {headers: phone()})).json()).cmds.length, 0);
    eq('صفحة الآيفون بتعرف إن الأندرويد متصل', (await fetch(S, {headers: viewer()})).headers.get('x-live'), '1');
    ws.close(); await new Promise(r => setTimeout(r, 500));
    eq('… ولما يقفل بتعرف', (await fetch(S, {headers: viewer()})).headers.get('x-live'), '0');
    eq('طلب والأندرويد مش متصل بيستنى', (await (await fetch(C, {method: 'POST', headers: viewer(), body: sealCmd(L.key, {...cmd1, id: 'second123456'})})).json()).live, false);
    eq('… ويلاقيه أول ما يتصل', (await (await fetch(C, {headers: phone()})).json()).cmds.length, 1);
    await fetch(C + '?upto=999', {method: 'DELETE', headers: phone()});

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
    eq('مفيش شريط تحت لما كله تمام', /آخر تحديث من الموبايل/.test(txt), false);
    eq('مفيش تنبيه بطارية الأندرويد على الآيفون', await pg.locator('.minibat').count(), 0);
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
    // التحويل من الآيفون: الصفحة بتقفل الطلب بالمفتاح وتبعته، والأندرويد بيسمع ويفتحه
    const ws2 = await wsOpen(PORT, '/api/s/' + L.id + '/ws', L.tok);
    await pg.evaluate(() => App.reload()); await pg.waitForTimeout(800);
    eq('زرار التحويل ظاهر على الآيفون', await pg.isVisible('[data-act=xfer]'), true);
    await pg.click('[data-act=xfer]'); await pg.waitForTimeout(400);
    eq('بيقول إن الأندرويد متصل', (await pg.innerText('#sheet')).includes('الأندرويد متصل دلوقتي'), true);
    await pg.fill('#xf_to', '٠١٠ ١٢٣٤ ٥٦٧٨'); await pg.fill('#xf_a', '750'); await pg.fill('#xf_pin', '654321');
    await pg.click('[data-act=xnext]'); await pg.waitForTimeout(300);
    eq('بيأكد الرقم والمبلغ قبل ما يبعت', /750[\s\S]*01012345678/.test(await pg.innerText('#sheet')), true);
    await pg.screenshot({path: (process.env.SHOT || '/tmp/wallet') + '-xfer.png'});
    await pg.click('[data-act=xgo]');
    eq('الأندرويد سمع الطلب', await ws2.next().catch(e => e.message), 'cmd');
    const l2 = await (await fetch(C, {headers: phone()})).json();
    const c2 = l2.cmds.length && openCmd(L.key, Buffer.from(l2.cmds[l2.cmds.length - 1].b, 'base64url'));
    eq('الطلب اتفتح على الأندرويد صح', c2 && [c2.k, c2.to, c2.a, c2.pin, c2.w].join('/'), 'xfer/01012345678/750/654321/vfs3');
    eq('… وجديد', c2 && Math.abs(Date.now() - c2.t) < 60000, true);
    await pg.waitForFunction(() => document.body.innerText.includes('اتبعت للأندرويد'), null, {timeout: 5000}).catch(() => {});
    eq('الآيفون بيعرض إنه اتبعت', (await pg.innerText('body')).includes('اتبعت للأندرويد'), true);
    eq('الرقم السري مش محفوظ على الآيفون', await pg.evaluate(() => JSON.stringify(localStorage).includes('654321')), false);
    // الأندرويد خلّص ورفع النتيجة
    const done = sample(8713.7); done.data.xfer.list = [{id: c2.id, to: '01012345678', a: 750, from: 'iphone', w: 'vfs3', st: 'ok', msg: 'تم تحويل 750 جنيه بنجاح', at: Date.now(), up: Date.now()}];
    await fetch(S, {method: 'PUT', headers: phone(), body: seal(L.key, 1, zlib.gzipSync(Buffer.from(JSON.stringify(done))))});
    await pg.evaluate(() => App.reload());
    await pg.waitForFunction(() => document.body.innerText.includes('اتحوّل'), null, {timeout: 8000}).catch(() => {});
    eq('الآيفون بيعرض إن التحويل تم', (await pg.innerText('body')).includes('اتحوّل'), true);
    await fetch(C + '?upto=999', {method: 'DELETE', headers: phone()});
    ws2.close();
    // تحديث من الموبايل بيوصل لوحده
    await fetch(S, {method: 'PUT', headers: phone(), body: snap(L.key, 9999)});
    await pg.evaluate(() => App.reload());
    await pg.waitForFunction(() => document.body.innerText.includes('9,999'), null, {timeout: 8000}).catch(() => {});
    eq('الرقم الجديد وصل', (await pg.innerText('body')).includes('9,999'), true);
    eq('مفيش رسالة «من الأندرويد بس» مع كل تحديث', await pg.evaluate(() => document.getElementById('toast').className.includes('show') && document.getElementById('toast').textContent.includes('الأندرويد بس')), false);
    // والإشعارات شغالة كمان: الصفحة بتجيب الجديد لوحدها من غير ما حد يدوس (كانت بتستنى الإشعار بس)
    await fetch(S, {method: 'PUT', headers: phone(), body: snap(L.key, 4321)});
    await pg.waitForFunction(() => document.body.innerText.includes('4,321'), null, {timeout: 25000}).catch(() => {});
    eq('الحساب الجديد بيظهر لوحده والصفحة مفتوحة', (await pg.innerText('body')).includes('4,321'), true);
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
