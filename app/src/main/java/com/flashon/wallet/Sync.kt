package com.flashon.wallet

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Watch from another phone": the screen data is encrypted on this phone and uploaded to
 * wallet.flashoneg.com. The key never leaves the phone except inside the link (after #),
 * and browsers never send that part to the server, so the server only holds scrambled bytes.
 */
object Sync {
    const val HOST = "https://wallet.flashoneg.com"
    private const val MIN_GAP = 8_000L
    private val LOCK = Any()
    private val rnd = SecureRandom()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun isOn(store: Store) = store.getStr("sync_id") != null

    fun link(store: Store): String? {
        val id = store.getStr("sync_id") ?: return null
        val key = store.getStr("sync_key") ?: return null
        return "$HOST/#$id.$key"
    }

    fun state(store: Store): JSONObject = JSONObject()
        .put("on", isOn(store))
        .put("link", link(store) ?: JSONObject.NULL)
        .put("last", store.getLong("sync_ok"))
        .put("error", store.getStr("sync_err") ?: JSONObject.NULL)

    /** Turns sharing on with a brand new link (the old one, if any, stops working). Blocking. */
    fun enable(ctx: Context, store: Store) {
        disable(store)
        store.putStr("sync_id", b64(random(12)))
        store.putStr("sync_key", b64(random(32)))
        store.putStr("sync_tok", b64(random(24)))
        store.putStr("sync_hash", "")
        pushNow(ctx, store, force = true)
    }

    /** Turns sharing off and wipes the copy on the server. Blocking. */
    fun disable(store: Store) {
        val id = store.getStr("sync_id") ?: return
        val tok = store.getStr("sync_tok") ?: ""
        try {
            val c = URL("$HOST/api/s/$id").openConnection() as HttpURLConnection
            c.requestMethod = "DELETE"
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Authorization", "Bearer $tok")
            c.responseCode
            c.disconnect()
        } catch (_: Exception) { }
        for (k in listOf("sync_id", "sync_key", "sync_tok", "sync_hash", "sync_err")) store.remove(k)
        store.putLong("sync_ok", 0)
    }

    /** Called after every screen rebuild; uploads only when something changed. Blocking. */
    fun pushIfOn(ctx: Context, store: Store, screenJson: String? = null) {
        if (!isOn(store)) return
        pushNow(ctx, store, screenJson = screenJson)
    }

    private fun pushNow(ctx: Context, store: Store, force: Boolean = false, screenJson: String? = null) = synchronized(LOCK) {
        val id = store.getStr("sync_id") ?: return
        val key = store.getStr("sync_key") ?: return
        val tok = store.getStr("sync_tok") ?: return
        try {
            val o = JSONObject(screenJson ?: DataJson.build(ctx, store))
            // the other phone only looks: drop what belongs to this phone alone
            o.remove("sync"); o.remove("update"); o.remove("batteryOk")
            // full message text, so opening a transaction there shows the SMS too
            val ids = HashSet<String>()
            val txs = o.optJSONArray("txs")
            if (txs != null) for (i in 0 until txs.length()) ids.add(txs.getJSONObject(i).optString("id"))
            val bodies = JSONObject()
            for (s in store.allSms()) if (s.id in ids) bodies.put(s.id, s.body)
            o.put("bodies", bodies)
            val plain = o.toString()

            val hash = b64(MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8)))
            if (!force && hash == store.getStr("sync_hash")) return
            if (!force && System.currentTimeMillis() - store.getLong("sync_try") < MIN_GAP) {
                // too soon after the last upload: try once more a bit later
                scheduleRetry(ctx); return
            }
            store.putLong("sync_try", System.currentTimeMillis())

            val wrapped = JSONObject().put("t", System.currentTimeMillis()).put("data", o).toString()
            val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(wrapped.toByteArray(Charsets.UTF_8)) } }.toByteArray()
            val iv = random(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(unb64(key), "AES"), GCMParameterSpec(128, iv))
            val sealed = cipher.doFinal(gz)
            val body = ByteArray(1 + iv.size + sealed.size)
            body[0] = 1
            System.arraycopy(iv, 0, body, 1, iv.size)
            System.arraycopy(sealed, 0, body, 1 + iv.size, sealed.size)

            val c = URL("$HOST/api/s/$id").openConnection() as HttpURLConnection
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer $tok")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            c.disconnect()
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
