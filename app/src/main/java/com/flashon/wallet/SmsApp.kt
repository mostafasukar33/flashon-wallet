package com.flashon.wallet

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fix that doesn't depend on the phone: محافظي as the phone's SMS app.
 *
 * Infinix / Tecno (XOS) freeze a closed app whatever it does. The server log (v19) showed it: the
 * foreground service was up, battery saving was off, and the alarm-clock alarm still stopped after
 * an hour and the app stayed frozen 9.4 hours, until it was opened.
 *
 * But Android hands every SMS to the default SMS app first (SMS_DELIVER), because that app is the
 * one that saves it in the inbox, and Android lets it run for that. A phone that held it back would
 * lose people's messages. So with محافظي as the SMS app, a wallet SMS reaches the iPhone the moment
 * it lands, frozen or not. The price: محافظي saves, shows and sends all the SMS on that phone (a
 * simple messages screen, a notification for each message); MMS isn't opened. The user can go back
 * to the old SMS app any time, and finds every message there.
 */
object SmsApp {
    private const val CH = "sms"
    const val ACTION_CHAT = "com.flashon.wallet.CHAT"

    fun isDefault(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        else Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
    } catch (_: Exception) { false }

    /** Can this phone make it the SMS app at all (a phone with SIM, a role that exists). */
    fun available(ctx: Context): Boolean = try {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            (Build.VERSION.SDK_INT < 29 || ctx.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_SMS) == true)
    } catch (_: Exception) { false }

    /** Android's own "make it the SMS app?" question, or null if it can't be asked (then: default apps settings). */
    fun requestIntent(ctx: Context): Intent? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) rm.createRequestRoleIntent(RoleManager.ROLE_SMS) else null
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
        }
    } catch (_: Exception) { null }

    /** For the screens: is it the SMS app, can it be, since when, unread messages. Keeps "since when" up to date. */
    fun json(ctx: Context, store: Store): JSONObject {
        val on = isDefault(ctx)
        val at = store.getLong("smsapp_at")
        if (on && at == 0L) store.putLong("smsapp_at", System.currentTimeMillis())
        if (!on && at != 0L) store.putLong("smsapp_at", 0)
        return JSONObject().put("on", on).put("can", available(ctx)).put("at", store.getLong("smsapp_at"))
            .put("unread", if (on) unread(ctx) else 0).put("lines", if (on) unreadByLine(ctx) else JSONArray())
    }

    private fun unread(ctx: Context): Int = try {
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID), Telephony.Sms.READ + "=0", null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    // ---------- incoming ----------

    /** Saves a new SMS in the phone's inbox (the SMS app's job: nobody else does it any more). Returns its conversation, or -1. */
    fun saveIncoming(ctx: Context, m: SmsReceiver.In): Long {
        val thread = try { Telephony.Threads.getOrCreateThreadId(ctx, m.sender) } catch (_: Exception) { -1L }
        // some phones save it themselves even for another SMS app: don't show it twice
        val already = try {
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                Telephony.Sms.ADDRESS + "=? AND " + Telephony.Sms.BODY + "=? AND " + Telephony.Sms.DATE + ">?",
                arrayOf(m.sender, m.body, (System.currentTimeMillis() - 120_000L).toString()), null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
        if (already) return thread
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, m.sender)
            put(Telephony.Sms.BODY, m.body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, m.sentAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.PROTOCOL, m.pid)
            m.center?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
            if (m.sub >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, m.sub)
            if (thread > 0) put(Telephony.Sms.THREAD_ID, thread)
        }
        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
        return thread
    }

    private fun channel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "الرسايل", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "كل رسالة بتوصل الموبايل، لما «محافظي» يكون تطبيق الرسايل"
            })
        }
        return nm
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun noteId(thread: Long, sender: String) = 50000 + (if (thread > 0) (thread % 100000).toInt() else (sender.hashCode() and 0xffff))

    /** A notification for a new SMS, like any messages app. Tapping it opens that conversation. */
    fun notify(ctx: Context, store: Store, m: SmsReceiver.In, thread: Long) {
        if (!canNotify(ctx) || MainActivity.viewing(thread)) return
        val nm = channel(ctx) ?: return
        val id = noteId(thread, m.sender)
        val open = PendingIntent.getActivity(ctx, id,
            Intent(ctx, MainActivity::class.java).setAction(ACTION_CHAT).putExtra("thread", thread).putExtra("addr", m.sender)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF1E5AA8.toInt())
            .setContentTitle(Names(ctx, store).of(m.sender) ?: m.sender)
            .setContentText(m.body)
            .setStyle(Notification.BigTextStyle().bigText(m.body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    fun cancelNote(ctx: Context, thread: Long, sender: String) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(noteId(thread, sender)) } catch (_: Exception) { }
    }

    /** A picture/video message (MMS): محافظي doesn't open those, so it only says one came. */
    fun notifyMms(ctx: Context) {
        if (!canNotify(ctx)) return
        val nm = channel(ctx) ?: return
        val open = PendingIntent.getActivity(ctx, 49999, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "وصلت رسالة فيها صورة أو فيديو (MMS)، و«محافظي» مبيفتحش النوع ده. لو مستنيها، رجّع تطبيق الرسايل القديم من الإعدادات ← التطبيقات الافتراضية."
        nm.notify(49999, Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF1E5AA8.toInt())
            .setContentTitle("رسالة صور أو فيديو")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    // ---------- sending ----------

    private fun manager(ctx: Context, sub: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (base != null) return if (sub >= 0) base.createForSubscriptionId(sub) else base
        }
        @Suppress("DEPRECATION")
        return if (sub >= 0) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
    }

    /** The SIM to send on: the conversation's, else the phone's SMS SIM, else the one the last SMS came on. */
    private fun pickSub(ctx: Context, hint: Int): Int {
        if (valid(hint)) return hint
        val def = try { SmsManager.getDefaultSmsSubscriptionId() } catch (_: Exception) { -1 }
        if (valid(def)) return def
        return subs(ctx).firstOrNull() ?: -1
    }

    /** A SIM that's in the phone now (an old id from a removed SIM fails every send). */
    fun valid(sub: Int): Boolean {
        if (sub < 0 || sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return false
        if (Build.VERSION.SDK_INT >= 29) return try { SubscriptionManager.getSlotIndex(sub) >= 0 } catch (_: Exception) { true }
        return true
    }

    /** The SIMs messages came on lately and still in the phone, line 1 first (no phone permission needed). */
    fun subs(ctx: Context): List<Int> {
        val seen = LinkedHashSet<Int>()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.SUBSCRIPTION_ID), null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 3000) if (!c.isNull(0)) seen += c.getInt(0)
            }
        } catch (_: Exception) { }
        val ok = seen.filter { valid(it) }
        return if (Build.VERSION.SDK_INT >= 29) ok.sortedBy { try { SubscriptionManager.getSlotIndex(it) } catch (_: Exception) { 9 } } else ok.sorted()
    }

    private fun markFailed(ctx: Context, rowId: Long, why: String) {
        try { ctx.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId), ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null) } catch (_: Exception) { }
        noteFail(ctx, rowId, why)
    }

    /** Why a send failed, by message, for the screen and the server log. */
    fun noteFail(ctx: Context, rowId: Long, why: String) {
        val st = Store(ctx)
        st.putStr("sms_last", why)
        if (rowId <= 0) return
        val o = try { JSONObject(st.getStr("sms_fail") ?: "{}") } catch (_: Exception) { JSONObject() }
        o.put(rowId.toString(), why)
        val keys = o.keys().asSequence().toList()
        if (keys.size > 60) for (k in keys.take(keys.size - 60)) o.remove(k)
        st.putStr("sms_fail", o.toString())
    }

    fun failOf(ctx: Context): JSONObject = try { JSONObject(Store(ctx).getStr("sms_fail") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Removes one SMS (a failed one, before sending it again). */
    fun delete(ctx: Context, id: Long) {
        try { ctx.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) } catch (_: Exception) { }
    }

    /** Sends an SMS and keeps it in the conversation (the SMS app has to save what it sends too). Blocking. */
    fun send(ctx: Context, to: String, text: String, subHint: Int): Boolean {
        val app = ctx.applicationContext
        val body = text.trim()
        val addrs = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (addrs.isEmpty() || body.isEmpty()) return false
        val sub = pickSub(app, subHint)
        var ok = true
        for (addr in addrs) {
            var rowId = -1L
            try {
                val sm = manager(app, sub)
                val thread = try { Telephony.Threads.getOrCreateThreadId(app, addr) } catch (_: Exception) { -1L }
                var uri: Uri? = null
                if (isDefault(app)) uri = try {
                    app.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, addr)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                        if (sub >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
                        if (thread > 0) put(Telephony.Sms.THREAD_ID, thread)
                    })
                } catch (_: Exception) { null }
                rowId = uri?.lastPathSegment?.toLongOrNull() ?: -1L
                val parts = sm.divideMessage(body)
                // mutable, so the phone can add its error code when it fails (shown on the screen)
                val mut = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sent = ArrayList<PendingIntent?>()
                for (i in parts.indices) sent += if (i == parts.size - 1 && rowId > 0)
                    PendingIntent.getBroadcast(app, (rowId % Int.MAX_VALUE).toInt(),
                        Intent(app, SmsSentReceiver::class.java).setData(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId)),
                        mut or PendingIntent.FLAG_ONE_SHOT)
                else null
                if (parts.size > 1) sm.sendMultipartTextMessage(addr, null, parts, sent, null)
                else sm.sendTextMessage(addr, null, body, sent.firstOrNull(), null)
            } catch (e: Exception) {
                ok = false
                // it never left: don't leave it "sending" forever
                val why = (if (e is SecurityException) "perm" else "ex") + ":" + e.javaClass.simpleName + ";sub=" + sub
                if (rowId > 0) markFailed(app, rowId, why) else noteFail(app, -1, why)
            }
        }
        return ok
    }

    // ---------- the messages screen ----------

    /** Conversations, newest first: name, last message, unread count. Blocking. */
    /**
     * Conversations, newest first, one per conversation and SIM line (the screen shows a tab for each line):
     * name, last message on that line, unread count. A SIM that isn't in the phone any more counts as line 1.
     */
    fun threads(ctx: Context, store: Store): JSONObject {
        val out = JSONArray()
        val byKey = HashMap<String, JSONObject>()
        var unread = 0
        val names = Names(ctx, store)
        val lines = subs(ctx)
        val perLine = LinkedHashMap<Int, Int>().apply { for (x in lines) put(x, 0) }
        fun lineOf(sub: Int) = if (sub in lines) sub else (lines.firstOrNull() ?: -1)
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID),
                null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < 5000) {
                    val type = c.getInt(4)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    val t = c.getLong(0)
                    val line = lineOf(if (c.isNull(6)) -1 else c.getInt(6))
                    val key = "$t/$line"
                    var j = byKey[key]
                    if (j == null) {
                        if (byKey.size >= 300) continue
                        val addr = c.getString(1) ?: ""
                        j = JSONObject().put("id", t.toString()).put("addr", addr).put("name", names.of(addr) ?: JSONObject.NULL)
                            .put("snippet", (c.getString(2) ?: "").replace('\n', ' ').take(140)).put("time", c.getLong(3))
                            .put("out", type != Telephony.Sms.MESSAGE_TYPE_INBOX).put("unread", 0).put("sub", line)
                        byKey[key] = j
                        out.put(j)
                    }
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0) {
                        j.put("unread", j.getInt("unread") + 1); unread++
                        perLine[line] = (perLine[line] ?: 0) + 1
                    }
                }
            }
        } catch (_: Exception) { }
        val ls = JSONArray(); for ((sub, n) in perLine) ls.put(JSONObject().put("sub", sub).put("unread", n))
        return JSONObject().put("threads", out).put("unread", unread).put("lines", ls)
    }

    /**
     * The last messages for the iPhone (the same tabs and conversations there, to read): newest first, as
     * [thread, address, name, text, time, out (0/1), SIM line, read (0/1)], and unread per line. It goes up
     * inside the account, locked with the link's password like everything else. Blocking.
     */
    fun export(ctx: Context, store: Store, max: Int = 400): JSONObject {
        val names = Names(ctx, store)
        val lines = subs(ctx)
        val msgs = JSONArray()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID),
                null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
                while (c.moveToNext() && msgs.length() < max) {
                    val type = c.getInt(4)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    val addr = c.getString(1) ?: ""
                    val sub = if (c.isNull(6)) -1 else c.getInt(6)
                    msgs.put(JSONArray().put(c.getLong(0)).put(addr).put(names.of(addr) ?: "").put((c.getString(2) ?: "").take(600))
                        .put(c.getLong(3)).put(if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) 0 else 1)
                        .put(if (sub in lines) sub else (lines.firstOrNull() ?: -1)).put(c.getInt(5)))
                }
            }
        } catch (_: Exception) { }
        return JSONObject().put("lines", unreadByLine(ctx)).put("msgs", msgs)
    }

    /** Unread messages per SIM line, line 1 first (the messages card on the main screen). */
    fun unreadByLine(ctx: Context): JSONArray {
        val lines = subs(ctx)
        val per = LinkedHashMap<Int, Int>().apply { for (x in lines) put(x, 0) }
        try {
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.SUBSCRIPTION_ID), Telephony.Sms.READ + "=0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val sub = if (c.isNull(0)) -1 else c.getInt(0)
                    val line = if (sub in lines) sub else (lines.firstOrNull() ?: -1)
                    per[line] = (per[line] ?: 0) + 1
                }
            }
        } catch (_: Exception) { }
        val out = JSONArray(); for ((sub, n) in per) out.put(JSONObject().put("sub", sub).put("unread", n))
        return out
    }

    /** One conversation, oldest first; marks it read. [threadId] <= 0: the conversation with [addr] (a new one if none). Blocking. */
    fun chat(ctx: Context, store: Store, threadId: Long, addr: String): JSONObject {
        var t = threadId
        if (t <= 0 && addr.isNotBlank()) t = try { Telephony.Threads.getOrCreateThreadId(ctx, addr) } catch (_: Exception) { -1L }
        var a = addr
        var sub = -1
        val rows = ArrayList<JSONObject>()
        val fails = failOf(ctx)
        if (t > 0) try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID),
                Telephony.Sms.THREAD_ID + "=?", arrayOf(t.toString()), Telephony.Sms.DATE + " DESC")?.use { c ->
                while (c.moveToNext() && rows.size < 400) {
                    val type = c.getInt(4)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    if (a.isBlank()) a = c.getString(1) ?: ""
                    if (sub < 0 && !c.isNull(5)) sub = c.getInt(5)
                    rows += JSONObject().put("id", c.getLong(0)).put("body", c.getString(2) ?: "").put("time", c.getLong(3))
                        .put("out", type != Telephony.Sms.MESSAGE_TYPE_INBOX)
                        .put("err", if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) fails.optString(c.getLong(0).toString()) else "")
                        .put("st", when (type) {
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> 1
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> 2
                            else -> 0
                        })
                }
            }
        } catch (_: Exception) { }
        val msgs = JSONArray()
        for (i in rows.indices.reversed()) msgs.put(rows[i])
        if (t > 0 && isDefault(ctx)) {
            try {
                ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) },
                    Telephony.Sms.THREAD_ID + "=? AND " + Telephony.Sms.READ + "=0", arrayOf(t.toString()))
            } catch (_: Exception) { }
            cancelNote(ctx, t, a)
        }
        return JSONObject().put("id", if (t > 0) t.toString() else "").put("addr", a)
            .put("name", Names(ctx, store).of(a) ?: JSONObject.NULL).put("sub", if (valid(sub)) sub else pickSub(ctx, -1)).put("subs", JSONArray(subs(ctx))).put("msgs", msgs).put("unread", unread(ctx))
    }

    /** Names for numbers: the phone's contacts if allowed, else the names the user gave in محافظي. */
    class Names(private val ctx: Context, store: Store) {
        private val learned: Map<String, String> = try { store.learned().names } catch (_: Exception) { emptyMap() }
        private val contacts = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        private val cache = HashMap<String, String?>()

        fun of(addr: String): String? {
            if (addr.isBlank()) return null
            if (cache.containsKey(addr)) return cache[addr]
            val n = contact(addr) ?: local(addr)
            cache[addr] = n
            return n
        }

        private fun local(a: String): String? {
            val d = a.filter { it.isDigit() }
            val eg = when {
                d.startsWith("0020") -> "0" + d.substring(4)
                d.startsWith("20") && d.length == 12 -> "0" + d.substring(2)
                else -> d
            }
            return learned[eg]?.takeIf { it.isNotBlank() } ?: learned[a]?.takeIf { it.isNotBlank() }
        }

        private fun contact(a: String): String? {
            if (!contacts || a.count { it.isDigit() } < 5) return null
            return try {
                ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(a)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
                }
            } catch (_: Exception) { null }
        }
    }
}

/**
 * Every SMS, first, because محافظي is the SMS app (SMS_DELIVER): saved in the inbox, a wallet SMS
 * sent to the iPhone, a notification for the rest. Android lets the SMS app run for this even when
 * the phone froze it, so this is what makes the iPhone hear about a transfer the moment it lands.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchService.saw("dlv")
        val got = SmsReceiver.split(context, intent)
        if (got.isEmpty()) return
        val app = context.applicationContext
        // the iPhone's wake-up SMS: fetch its transfer request now; it isn't a message to keep or show
        if (got.any { Remote.isWake(it.body) }) { WatchService.saw("wake"); Remote.ring(app) }
        val all = got.filter { !Remote.isWake(it.body) }
        if (all.isEmpty()) return
        val pending = goAsync()
        val wl = SmsSource.awake(app, "dlv", 90_000L)
        val done = CountDownLatch(1)
        Thread {
            try {
                val store = Store(app)
                // saved first, so nothing is lost whatever happens next
                val threads = all.map { m -> try { SmsApp.saveIncoming(app, m) } catch (_: Exception) { -1L } }
                val isWallet = all.map { SmsSource.isWallet(it.sender, it.body) }
                for (i in all.indices) if (!isWallet[i]) try { SmsApp.notify(app, store, all[i], threads[i]) } catch (_: Exception) { }
                val wallet = all.filterIndexed { i, _ -> isWallet[i] }
                // the iPhone, then this phone's money notification and the screens
                if (wallet.isNotEmpty()) try { SmsSource.deliver(app, wallet.map { it.raw() }, "dlv", SmsReceiver.lagOf(wallet)) } catch (_: Exception) { }
                // money notifications turned off: still a plain one, as the SMS app is the only one that notifies now
                if (!store.txNotify) for (i in all.indices) if (isWallet[i]) try { SmsApp.notify(app, store, all[i], threads[i]) } catch (_: Exception) { }
                MainActivity.smsChanged()
                // the iPhone's messages tabs too (locked with the link's password like the rest)
                if (Sync.isOn(store)) try { Sync.pushIfOn(app, store) } catch (_: Exception) { }
            } catch (_: Exception) {
            } finally {
                done.countDown()
                try { if (wl.isHeld) wl.release() } catch (_: Exception) { }
            }
        }.start()
        // the broadcast stays open while it sends (Android keeps the app running for it), but at most
        // 8 seconds, so the other apps' copy of the SMS isn't held up
        Thread { try { done.await(8, TimeUnit.SECONDS) } catch (_: Exception) { }; pending.finish() }.start()
        WatchService.start(app)
    }
}

/** MMS (pictures, video): needed to be the SMS app; محافظي only says one came. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { SmsApp.notifyMms(context) } catch (_: Exception) { }
    }
}

/** An SMS this app sent: sent or failed, shown in the conversation. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val type = if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        try { context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null) } catch (_: Exception) { }
        // the phone's reason (result code / network error code), for the screen and the server log
        val why = if (ok) "ok" else "r" + resultCode + "/" + intent.getIntExtra("errorCode", -1)
        SmsApp.noteFail(context, if (ok) -1 else (uri.lastPathSegment?.toLongOrNull() ?: -1), why)
        MainActivity.smsChanged()
    }
}

/** "Reply with a message" when rejecting a call: needed to be the SMS app. */
class RespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')?.removePrefix("//") ?: ""
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        Thread {
            try { if (to.isNotBlank() && text.isNotBlank()) SmsApp.send(this, to, text, -1) } catch (_: Exception) { }
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }
}
