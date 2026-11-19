package com.flashon.wallet

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * The fix that keeps the phone's own SMS app: Android's "notification access".
 *
 * The phone's SMS app stays the SMS app (it saves, notifies and sends like always). When it shows its
 * notification for a new SMS, Android hands that notification to محافظي too, the same instant. Android
 * itself keeps a notification listener connected (it binds it like a foreground service, and binds it
 * again if the process dies), which is what smart-watch apps rely on, so the phone doesn't freeze it
 * like an ordinary closed app.
 *
 * The notification is only the signal: the message is read from the inbox (the SMS app saved it before
 * notifying), so the ledger gets exactly the same text as before. Nothing from the notification is kept.
 */
class NoteListener : NotificationListenerService() {
    companion object {
        @Volatile var connected = false
            private set
        @Volatile var connectedAt = 0L
            private set

        /** SMS apps by their package, besides whichever is the phone's SMS app right now. */
        private val SMS_APPS = setOf(
            "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging",
            "com.samsung.android.messaging", "com.transsion.message", "com.transsion.messages",
            "com.oneplus.mms", "com.coloros.mms", "com.oplus.message", "com.miui.mms", "com.huawei.message",
            "com.hihonor.mms", "com.vivo.message", "com.asus.message", "com.motorola.messaging", "com.sonyericsson.conversations"
        )

        fun component(ctx: Context) = ComponentName(ctx, NoteListener::class.java)

        /** Did the user turn on notification access for محافظي. */
        fun granted(ctx: Context): Boolean = try {
            if (Build.VERSION.SDK_INT >= 27) ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))
            else (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").split(':')
                .any { ComponentName.unflattenFromString(it) == component(ctx) }
        } catch (_: Exception) { false }

        /** The page with the switch for محافظي itself (Android 11+), else the list of apps with notification access. */
        fun settingsIntents(ctx: Context): List<Intent> {
            val list = ArrayList<Intent>()
            if (Build.VERSION.SDK_INT >= 30) list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString())
            list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            list += Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS")
            return list
        }

        /** Allowed but not connected (the phone dropped it): ask Android to connect it again. */
        fun rebind(ctx: Context) {
            if (connected || !granted(ctx)) return
            try { requestRebind(component(ctx)) } catch (_: Exception) { }
        }

        /** For the screens: is it on, connected, and since when (to judge a late SMS after turning it on). */
        fun json(ctx: Context, store: Store): JSONObject {
            val on = granted(ctx)
            val at = store.getLong("nl_at")
            if (on && at == 0L) store.putLong("nl_at", System.currentTimeMillis())
            if (!on && at != 0L) store.putLong("nl_at", 0)
            return JSONObject().put("on", on).put("live", connected).put("at", store.getLong("nl_at"))
        }

        /** Chrome's doorbell notification: the iPhone sent a transfer request. */
        fun isBell(n: Notification?): Boolean =
            n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.startsWith(Remote.BELL_TITLE) == true

        fun isSmsApp(ctx: Context, pkg: String?): Boolean {
            if (pkg.isNullOrBlank() || pkg == ctx.packageName) return false
            if (pkg in SMS_APPS) return true
            val def = try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
            return pkg == def || pkg.endsWith(".mms") || pkg.contains(".messaging") || pkg.endsWith(".message") || pkg.endsWith(".messages")
        }

        private val WALLET_WORDS = Regex("كاش|محفظ|cash|wallet|e& money|etisalat money", RegexOption.IGNORE_CASE)

        /** A notification that may be a new SMS: from an SMS app, or a message that talks about a wallet. */
        fun maybeSms(ctx: Context, pkg: String?, n: Notification?): Boolean {
            if (isSmsApp(ctx, pkg)) return true
            if (pkg == ctx.packageName || n == null || n.category != Notification.CATEGORY_MESSAGE) return false
            val e = n.extras ?: return false
            val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .joinToString(" ") { e.getCharSequence(it)?.toString() ?: "" }
            return WALLET_WORDS.containsMatchIn(text)
        }
    }

    override fun onListenerConnected() {
        connected = true
        connectedAt = System.currentTimeMillis()
        WatchService.saw("nl")
        WatchService.start(this)
        TickReceiver.next(this)   // with this on, no ⏰ every minute
        Poke.now(this, "nl")      // anything that came while it wasn't connected
    }

    override fun onListenerDisconnected() {
        connected = false
        try { requestRebind(component(this)) } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        // a transfer is running and the phone shows Vodafone's answer as a notification
        Xfer.job?.let { j -> if (!j.done) try { Xfer.onNote(this, s.packageName, s.notification) } catch (_: Exception) { } }
        // the doorbell from the iPhone: fetch the request, and the notification goes away (it's only a signal)
        if (isBell(s.notification)) {
            WatchService.saw("bell")
            Remote.ring(this)
            try { cancelNotification(s.key) } catch (_: Exception) { }
            return
        }
        if (!maybeSms(this, s.packageName, s.notification)) return
        WatchService.saw("ntf")
        Poke.now(this, "ntf")
    }
}

/**
 * "A new SMS may have landed": read the last days of the inbox and send what's new (the iPhone first).
 * Several signals for one SMS (the notification, its update, the accessibility event) become one read,
 * then two more a little later, for an SMS app that notifies a moment before it saves the message.
 */
object Poke {
    private val thread = HandlerThread("poke").also { it.start() }
    private val h = Handler(thread.looper)
    @Volatile private var via = "ntf"
    private lateinit var app: Context
    private val read = Runnable { try { SmsSource.catchUp(app, via) } catch (_: Exception) { } }

    fun now(ctx: Context, how: String) {
        app = ctx.applicationContext
        try { Remote.poke(app) } catch (_: Exception) { }   // awake anyway: a quick look for a transfer request from the iPhone
        via = how
        SmsSource.awake(app, "poke", 12_000L)
        h.removeCallbacks(read)
        h.postDelayed(read, 300)
        h.postDelayed(read, 2500)
        h.postDelayed(read, 7000)
    }
}
