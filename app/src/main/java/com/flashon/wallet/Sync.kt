package com.flashon.wallet

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Watch from another phone": the screen data is locked on this phone with a key made from the
 * user's password, then uploaded to w.flashoneg.com. The password never leaves the phone and is
 * not kept on it; the server only holds scrambled bytes and can't open them.
 * The link alone opens nothing: the other phone needs the password too.
 */
object Sync {
    const val HOST = "https://w.flashoneg.com"
    const val ROUNDS = 150_000          // same number in server/src/view.html
    private const val MIN_GAP = 8_000L
    private val LOCK = Any()
    private val rnd = SecureRandom()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    fun isOn(store: Store) = store.getStr("sync_key") != null

    fun link(store: Store): String? {
        val id = store.getStr("sync_id") ?: return null
        val salt = store.getStr("sync_salt") ?: return null
        return "$HOST/v/$id.$salt"
    }

    fun state(store: Store): JSONObject = JSONObject()
        .put("on", isOn(store))
        .put("link", link(store) ?: JSONObject.NULL)
        .put("last", store.getLong("sync_ok"))
        .put("error", store.getStr("sync_err") ?: JSONObject.NULL)

    /** Key from the password (PBKDF2-SHA256), and the proof the other phone shows the server to download. */
    private fun keyOf(password: String, salt: ByteArray): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)).encoded
    }
    private fun proofOf(key: ByteArray) = b64(sha(key + "sub".toByteArray()))

    /** Turns sharing on with a new link and password (the old link stops working). Blocking. */
    fun enable(ctx: Context, store: Store, password: String) {
        disable(store)
        val salt = random(16)
        val key = keyOf(password, salt)
        store.putStr("sync_id", b64(random(12)))
        store.putStr("sync_salt", b64(salt))
        store.putStr("sync_tok", b64(random(24)))
        store.putStr("sync_sub", proofOf(key))
        store.putStr("sync_hash", "")
        store.putStr("sync_key", b64(key))
        pushNow(ctx, store, force = true)
    }

    /** Turns sharing off and wipes everything on the server. Blocking. */
    fun disable(store: Store) {
        val id = store.getStr("sync_id")
        if (id != null) try { send("DELETE", "/api/s/$id", store, null) } catch (_: Exception) { }
        for (k in listOf("sync_id", "sync_salt", "sync_key", "sync_tok", "sync_sub", "sync_hash", "sync_err", "sync_pending")) store.remove(k)
        store.putLong("sync_ok", 0)
    }

    private fun send(method: String, path: String, store: Store, body: ByteArray?, extra: Map<String, String> = emptyMap()): Int {
        val c = URL(HOST + path).openConnection() as HttpURLConnection
        for ((k, v) in extra) c.setRequestProperty(k, v)
        c.requestMethod = method
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer " + (store.getStr("sync_tok") ?: ""))
        store.getStr("sync_sub")?.let { c.setRequestProperty("X-Sub", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        c.disconnect()
        return code
    }

    /** [version][iv 12][AES-GCM(data) + tag]. Version 1 = gzipped screen data, 2 = a notification. */
    private fun seal(store: Store, version: Int, data: ByteArray): ByteArray {
        val key = unb64(store.getStr("sync_key") ?: throw IllegalStateException())
        val iv = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(data)
        return byteArrayOf(version.toByte()) + iv + sealed
    }

    /** How the last new SMS was caught (rcv/watch/box/open/job/boot) and how many seconds after it landed: only for the server log. */
    @Volatile var via = ""
    @Volatile var lag = 0L
    @Volatile var diag = ""
    /** Seconds from when the network sent the last new wallet SMS to when the app caught it (-1 = unknown). */
    @Volatile var smsc = -1L

    /** The phone's state for the server log (no numbers or messages): Android version, maker, battery saving, the watcher, notifications. */
    fun diagOf(ctx: Context): String {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        return "sdk=" + android.os.Build.VERSION.SDK_INT + ";maker=" + android.os.Build.MANUFACTURER.take(20) +
            ";bat=" + (if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) 1 else 0) +
            ";watch=" + (if (WatchService.running) 1 else 0) +
            ";notif=" + (if (nm.areNotificationsEnabled()) 1 else 0) +
            ";rsms=" + (if (ctx.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) 1 else 0) +
            ";exact=" + (if (android.os.Build.VERSION.SDK_INT < 31 || ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) 1 else 0) +
            ";age=" + (if (WatchService.bornAt > 0) (System.currentTimeMillis() - WatchService.bornAt) / 1000 else -1) +
            ";seen=" + WatchService.seen.entries.joinToString(",") { it.key + ":" + (System.currentTimeMillis() - it.value) / 1000 + "x" + (WatchService.counts[it.key] ?: 0) } +
            Freeze.diag(Store(ctx)) + Freeze.notesDiag(ctx) +
            ";def=" + (if (SmsApp.isDefault(ctx)) 1 else 0) + ";sl=" + smsc +
            ";v=" + try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode } catch (_: Exception) { 0 }
    }

    private const val PENDING_MAX_AGE = 6 * 60 * 60 * 1000L
    private val PENDING_LOCK = Any()

    /**
     * Sends the same notifications the phone shows to the other phone, the moment the SMS arrives.
     * With no internet they wait on the phone and go out by themselves when it's back. Blocking.
     */
    fun notify(ctx: Context, store: Store, msgs: List<Notifier.Msg>) {
        if (!isOn(store) || msgs.isEmpty()) return
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (m in msgs) arr.put(JSONObject().put("title", m.title).put("body", m.text).put("tag", m.tag))
        if (!sendPush(store, arr)) {
            synchronized(PENDING_LOCK) {
                val q = pending(store)
                for (i in 0 until arr.length()) q.put(arr.getJSONObject(i).put("at", now))
                store.putStr("sync_pending", q.toString())
            }
            PushRetry.schedule(ctx)
        }
    }

    private fun pending(store: Store): JSONArray = try { JSONArray(store.getStr("sync_pending") ?: "[]") } catch (_: Exception) { JSONArray() }

    /** Sends what waited for the internet. Returns false if it should be tried again later. Blocking. */
    fun flushPending(store: Store): Boolean = synchronized(PENDING_LOCK) {
        if (!isOn(store)) { store.remove("sync_pending"); return true }
        val q = pending(store)
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        for (i in 0 until q.length()) { val o = q.getJSONObject(i); if (now - o.optLong("at") < PENDING_MAX_AGE) keep.put(o) }
        if (keep.length() == 0) { store.remove("sync_pending"); return true }
        // the last 10 is enough; the full list is on the screen anyway
        val send = JSONArray(); for (i in maxOf(0, keep.length() - 10) until keep.length()) send.put(keep.getJSONObject(i))
        if (!sendPush(store, send)) { store.putStr("sync_pending", keep.toString()); return false }
        store.remove("sync_pending")
        return true
    }

    private fun sendPush(store: Store, arr: JSONArray): Boolean {
        val id = store.getStr("sync_id") ?: return true
        return try {
            val code = send("POST", "/api/s/$id/push", store, seal(store, 2, arr.toString().toByteArray(Charsets.UTF_8)),
                mapOf("X-Via" to via.ifBlank { "retry" }, "X-Lag" to lag.toString(), "X-Diag" to diag))
            code in 200..299 || code == 400 || code == 403 || code == 404   // those won't get better by retrying
        } catch (_: Exception) { false }
    }

    /** Called after every screen rebuild; uploads only when something changed. Blocking. */
    fun pushIfOn(ctx: Context, store: Store, screenJson: String? = null) {
        if (!isOn(store)) return
        pushNow(ctx, store, screenJson = screenJson)
    }

    private fun pushNow(ctx: Context, store: Store, force: Boolean = false, screenJson: String? = null) = synchronized(LOCK) {
        val id = store.getStr("sync_id") ?: return
        if (!isOn(store)) return
        try {
            val o = JSONObject(screenJson ?: DataJson.build(ctx, store))
            // the other phone only looks: drop what belongs to this phone alone
            o.remove("sync"); o.remove("update"); o.remove("batteryOk"); o.remove("watch"); o.remove("notifOk"); o.remove("hold"); o.remove("notes"); o.remove("smsApp")
            // full message text, so opening a transaction there shows the SMS too
            val ids = HashSet<String>()
            val txs = o.optJSONArray("txs")
            if (txs != null) for (i in 0 until txs.length()) ids.add(txs.getJSONObject(i).optString("id"))
            val bodies = JSONObject()
            for (s in store.allSms()) if (s.id in ids) bodies.put(s.id, s.body)
            o.put("bodies", bodies)
            val plain = o.toString()

            val hash = b64(sha(plain.toByteArray(Charsets.UTF_8)))
            if (!force && hash == store.getStr("sync_hash")) return
            if (!force && System.currentTimeMillis() - store.getLong("sync_try") < MIN_GAP) {
                // too soon after the last upload: try once more a bit later
                scheduleRetry(ctx); return
            }
            store.putLong("sync_try", System.currentTimeMillis())

            val wrapped = JSONObject().put("t", System.currentTimeMillis()).put("data", o).toString()
            val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(wrapped.toByteArray(Charsets.UTF_8)) } }.toByteArray()
            val code = send("PUT", "/api/s/$id", store, seal(store, 1, gz), mapOf("X-Diag" to try { diagOf(ctx) } catch (_: Exception) { "" }))
            if (code in 200..299) {
                store.putStr("sync_hash", hash)
                store.putLong("sync_ok", System.currentTimeMillis())
                store.remove("sync_err")
            } else {
                store.putStr("sync_err", "السيرفر رد بـ $code")
            }
        } catch (e: Exception) {
            store.putStr("sync_err", "مفيش نت")
        }
    }

    @Volatile private var retryAt = 0L
    private fun scheduleRetry(ctx: Context) {
        val now = System.currentTimeMillis()
        if (retryAt > now) return
        retryAt = now + MIN_GAP
        val app = ctx.applicationContext
        Thread {
            try { Thread.sleep(MIN_GAP + 500) } catch (_: InterruptedException) { }
            retryAt = 0
            pushIfOn(app, Store(app))
        }.start()
    }
}
