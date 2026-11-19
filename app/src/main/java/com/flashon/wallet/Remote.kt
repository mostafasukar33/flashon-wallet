package com.flashon.wallet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transfers asked from the iPhone. The iPhone locks each request (number, amount, PIN) with the same key
 * as the account, so w.flashoneg.com only passes scrambled bytes along: it can't read one or make one.
 *
 * The phone keeps one quiet connection open to the server (a WebSocket that says nothing but a ping
 * every few minutes); the server says "cmd" on it the moment a request lands, and the phone fetches it
 * at once. A request older than 10 minutes, or one seen before, is never run. Off unless the user turns
 * on «التحويل من الآيفون» on this phone.
 */
object Remote {
    private const val MAX_AGE = 10 * 60_000L
    private const val PING_EVERY = 4 * 60_000L
    private val LOCK = Any()

    fun enabled(store: Store) = Sync.isOn(store) && Xfer.remoteOn(store)

    // ---------- requests ----------

    /** Fetches the waiting requests, runs the fresh ones and clears them on the server. Blocking. */
    fun fetch(ctx: Context) = synchronized(LOCK) {
        val app = ctx.applicationContext
        val store = Store(app)
        if (!Sync.isOn(store)) return
        val id = store.getStr("sync_id") ?: return
        val r = try { Sync.call("GET", "/api/s/$id/cmd", store) } catch (_: Exception) { return }
        if (r.first != 200) return
        val arr = try { JSONObject(r.second).optJSONArray("cmds") } catch (_: Exception) { null } ?: return
        var upto = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            upto = maxOf(upto, o.optLong("n"))
            try { run(app, store, Sync.unb64(o.optString("b"))) } catch (_: Exception) { }
        }
        if (upto > 0) try { Sync.call("DELETE", "/api/s/$id/cmd?upto=$upto", store) } catch (_: Exception) { }
        store.putLong("rm_fetch", System.currentTimeMillis())
        // how many doorbells (Chrome on this phone) the server can ring: shown in the settings
        try { val n = JSONObject(r.second).optLong("bells", -1); if (n >= 0 && n != store.getLong("bell_n")) { store.putLong("bell_n", n); MainActivity.refreshIfOpen() } } catch (_: Exception) { }
    }

    private fun run(app: Context, store: Store, sealed: ByteArray) {
        val plain = Sync.open(store, 3, sealed) ?: return   // not locked with this account's key: not from the iPhone
        val c = JSONObject(String(plain, Charsets.UTF_8))
        val cid = c.optString("id").filter { it.isLetterOrDigit() }.take(32)
        if (cid.length < 8 || seen(store, cid)) return
        if (c.optString("k") != "xfer") return
        val age = System.currentTimeMillis() - c.optLong("t")
        val to = c.optString("to"); val a = c.optString("a"); val w = c.optString("w")
        val base = JSONObject().put("to", to.take(20)).put("a", a.toLongOrNull() ?: 0).put("from", "iphone").put("w", w)
        val why = when {
            age > MAX_AGE || age < -3 * 60_000L -> "الطلب وصل الأندرويد متأخر، ومش هيتنفذ. ابعته تاني"
            !Xfer.remoteOn(store) -> "التحويل من الآيفون مقفول من إعدادات «محافظي» على الأندرويد"
            else -> null
        }
        if (why != null) {
            Xfer.record(store, cid, base.put("st", "fail").put("msg", why))
            try { Sync.notify(app, store, listOf(Notifier.Msg("✗ التحويل ماتمش", why, "x$cid"))) } catch (_: Exception) { }
            MainActivity.refreshIfOpen()
            try { Sync.pushIfOn(app, store) } catch (_: Exception) { }
            return
        }
        Xfer.start(app, to, a, c.optString("pin"), w, "iphone", cid)
    }

    /** Each request runs once, even if the server sends it again. */
    private fun seen(store: Store, id: String): Boolean {
        val arr = try { JSONArray(store.getStr("rm_seen") ?: "[]") } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) if (arr.optString(i) == id) return true
        val out = JSONArray().put(id)
        for (i in 0 until minOf(arr.length(), 99)) out.put(arr.optString(i))
        store.putStr("rm_seen", out.toString())
        return false
    }

    // ---------- the doorbell ----------

    /** The title of the doorbell notification Chrome shows (server/src/sw.txt). */
    const val BELL_TITLE = "🔔 محافظي"

    /**
     * A new one-time word for the doorbell page, registered on the server; returns the page to open in Chrome
     * (/bell#<id>.<word>), or null without internet. Blocking.
     */
    fun bellPage(ctx: Context): String? {
        val store = Store(ctx)
        val id = store.getStr("sync_id") ?: return null
        val word = Sync.randomWord()
        val r = try { Sync.call("POST", "/api/s/$id/belltok", store, word.toByteArray(Charsets.US_ASCII)) } catch (_: Exception) { return null }
        return if (r.first == 200) Sync.HOST + "/bell#" + id + "." + word else null
    }

    /** The doorbell rang (or something woke the app): fetch the requests now, and open the line again. */
    fun ring(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(Store(app))) return
        lastPoke = System.currentTimeMillis()
        val wl = SmsSource.awake(app, "bell", 60_000L)
        Thread { try { fetch(app); keep(app) } catch (_: Exception) { } finally { try { if (wl.isHeld) wl.release() } catch (_: Exception) { } } }.start()
    }

    @Volatile private var lastPoke = 0L

    /** Any notification woke the app (a new SMS…): a quick look for requests too, at most every 30 seconds. */
    fun poke(ctx: Context) {
        if (System.currentTimeMillis() - lastPoke < 30_000L) return
        ring(ctx)
    }

    // ---------- the quiet connection ----------

    @Volatile private var thread: Thread? = null
    @Volatile private var ws: Ws? = null
    @Volatile var state = "off"
        private set
    @Volatile private var lastPing = 0L
    @Volatile private var netCb: ConnectivityManager.NetworkCallback? = null

    /** Starts the connection if transfers from the iPhone are on (or checks on it). Safe to call any time. */
    fun keep(ctx: Context) {
        val app = ctx.applicationContext
        val store = Store(app)
        if (!enabled(store)) { stop(app); return }
        Beat.next(app)
        watchNet(app)
        val t = thread
        if (t != null && t.isAlive) { ping(); return }
        thread = Thread({ loop(app) }, "remote").apply { isDaemon = true; start() }
    }

    fun stop(ctx: Context) {
        val t = thread
        thread = null
        t?.interrupt()
        try { ws?.close() } catch (_: Exception) { }
        ws = null; state = "off"
        Beat.cancel(ctx.applicationContext)
    }

    /** A ping (the server answers "pong" without waking up); no answer since the last one = the line died. */
    private fun ping() {
        val w = ws ?: return
        val now = System.currentTimeMillis()
        if (lastPing > 0 && w.lastRx < lastPing && now - lastPing > 30_000L) { Thread { w.close() }.start(); return }
        if (now - lastPing < 150_000L) return
        lastPing = now
        Thread { try { w.send("ping") } catch (_: Exception) { try { w.close() } catch (_: Exception) { } } }.start()
    }

    @Volatile private var wait = 3_000L

    private fun loop(app: Context) {
        val me = Thread.currentThread()
        while (thread === me) {
            val store = Store(app)
            if (!enabled(store)) break
            val id = store.getStr("sync_id") ?: break
            var w: Ws? = null
            try {
                w = Ws.open(Sync.HOST.replace("https://", "wss://") + "/api/s/$id/ws",
                    mapOf("Authorization" to "Bearer " + (store.getStr("sync_tok") ?: ""), "User-Agent" to "flashon-wallet"), (PING_EVERY + 3 * 60_000L).toInt())
                ws = w; state = "on"; wait = 3_000L; lastPing = 0
                fetch(app)   // anything that came while the line was down
                while (thread === me) {
                    val m = w.read() ?: break
                    if (m == "cmd") {
                        val wl = SmsSource.awake(app, "cmd", 30_000L)
                        try { fetch(app) } finally { try { if (wl.isHeld) wl.release() } catch (_: Exception) { } }
                    }
                }
                state = "closed"
            } catch (e: Exception) {
                state = "err:" + (e.message ?: e.javaClass.simpleName).take(30)
            }
            try { w?.close() } catch (_: Exception) { }
            if (ws === w) ws = null
            if (thread !== me) break
            try { Thread.sleep(wait) } catch (_: InterruptedException) { }   // interrupted = the internet is back: try now
            wait = minOf(wait * 2, 5 * 60_000L)
        }
        if (thread === me) { thread = null; state = "off" }
    }

    /** Back on the internet (Wi-Fi or data): connect again right away instead of waiting. */
    private fun watchNet(app: Context) {
        if (netCb != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (state == "on") return
                wait = 3_000L
                thread?.interrupt()
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (_: Exception) { }
    }

    /** For the server log. */
    fun diag(): String = ";ws=" + state.take(24)
}

/**
 * Every few minutes while transfers from the iPhone are on: keeps the quiet connection alive (a ping) or
 * opens it again. A plain alarm, not the ⏰ kind, so nothing shows at the top.
 */
class Beat : BroadcastReceiver() {
    companion object {
        private const val EVERY = 4 * 60_000L
        private fun pi(app: Context) = PendingIntent.getBroadcast(app, 6, Intent(app, Beat::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun next(ctx: Context) {
            val app = ctx.applicationContext
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + EVERY
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(app))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(app))
            } catch (_: Exception) { try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(app)) } catch (_: Exception) { } }
        }

        fun cancel(ctx: Context) {
            try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx.applicationContext)) } catch (_: Exception) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        WatchService.saw("beat")
        SmsSource.awake(context, "beat", 15_000L)
        Remote.keep(context)
        if (!WatchService.running) WatchService.start(context)
    }
}
