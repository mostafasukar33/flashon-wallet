package com.flashon.wallet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import com.flashon.wallet.core.Parser
import com.flashon.wallet.core.RawSms

object SmsSource {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun isWallet(sender: String, body: String) = Parser.walletOf(sender, Parser.normalize(body)) != null

    /** Reads every wallet message already in the phone's inbox. */
    private const val FULL_SCAN_VERSION = 3

    /**
     * full = read every wallet message in the phone (first run, "read again", after an update
     * that needs it). Otherwise only the last few days, which is fast.
     */
    fun scanInbox(ctx: Context, full: Boolean = false): Int {
        if (!hasPermission(ctx)) return 0
        val store = Store(ctx)
        val doFull = full || store.getLong("fullScanV") < FULL_SCAN_VERSION
        val since = if (doFull) 0L else (store.getLong("lastScan") - 3L * 24 * 3600 * 1000).coerceAtLeast(0L)
        val startedAt = System.currentTimeMillis()
        val found = readInbox(ctx, since)
        val added = store.addSms(found, trustSim = true)
        store.putLong("lastScan", startedAt)
        if (doFull) store.putLong("fullScanV", FULL_SCAN_VERSION.toLong())
        return added
    }

    private fun readInbox(ctx: Context, since: Long): List<RawSms> {
        val found = ArrayList<RawSms>()
        val where = "(" + Telephony.Sms.DATE + " > ?) AND (" +
            listOf("body LIKE '%كاش%'", "body LIKE '%cash%'", "body LIKE '%محفظ%'", "body LIKE '%wallet%'",
                "address LIKE '%cash%'").joinToString(" OR ") + ")"
        val args = arrayOf(since.toString())
        try {
            // all columns, because the SIM column name differs between phones (Samsung: sim_slot)
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, null, where, args, Telephony.Sms.DATE + " ASC"
            )?.use { c ->
                val ia = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val ib = c.getColumnIndex(Telephony.Sms.BODY)
                val id = c.getColumnIndex(Telephony.Sms.DATE)
                val iSlot = listOf("sim_slot", "sim_id", "slot", "phone_id").map { c.getColumnIndex(it) }.firstOrNull { it >= 0 } ?: -1
                val iSub = c.getColumnIndex("sub_id")
                while (c.moveToNext()) {
                    val sender = c.getString(ia) ?: continue
                    val body = c.getString(ib) ?: continue
                    if (!isWallet(sender, body)) continue
                    val slot = if (iSlot >= 0 && !c.isNull(iSlot)) c.getInt(iSlot) else -1
                    val sub = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub) else -1
                    found += RawSms(Store.idOf(sender, body), sender, body, c.getLong(id), simKey(ctx, slot, sub))
                }
            }
        } catch (_: Exception) { }
        return found
    }

    private val DELIVER_LOCK = Any()
    private const val LATE_MAX = 6 * 60 * 60 * 1000L

    /**
     * New wallet messages, however they were caught (the SMS broadcast, the inbox watcher in
     * [WatchService], or opening the app): the other phone first, then this phone's notification,
     * then the screens. A message that was already handled adds nothing and sends nothing. Blocking.
     */
    fun deliver(ctx: Context, list: List<RawSms>, via: String = "", smscLagMs: Long = -1) {
        if (list.isEmpty()) return
        // the screen is off: without this the phone sleeps right after the SMS and the send waits until it's picked up
        val wl = awake(ctx, "deliver", 90_000L)
        try { deliverLocked(ctx, list, via, smscLagMs) } finally { try { if (wl.isHeld) wl.release() } catch (_: Exception) { } }
    }

    fun awake(ctx: Context, tag: String, ms: Long): android.os.PowerManager.WakeLock =
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "flashon:$tag").apply { setReferenceCounted(false); acquire(ms) }

    private fun deliverLocked(ctx: Context, list: List<RawSms>, via: String, smscLagMs: Long) = synchronized(DELIVER_LOCK) {
        val store = Store(ctx)
        val before = store.allSms().map { it.id }.toHashSet()
        if (store.addSms(list) == 0) return
        val now = System.currentTimeMillis()
        // a message older than a few hours (first read of the inbox, "read again") is not news
        val fresh = list.filter { it.id !in before && now - it.time < LATE_MAX }.distinctBy { it.id }
        if (fresh.isNotEmpty()) {
            // how it was caught and how late (seconds), sent along so the server log shows where a delay comes from
            Sync.via = via; Sync.lag = ((now - fresh.minOf { it.time }) / 1000).coerceAtLeast(0)
            Freeze.caught(store, Sync.lag * 1000)
            // the real delay, from the time the network sent it (a caught broadcast is stamped "now", so lag says 0 there)
            Sync.smsc = if (smscLagMs >= 0) smscLagMs / 1000 else -1
            Sync.diag = try { Sync.diagOf(ctx) } catch (_: Exception) { "" }
            // the other phone first: read the new SMS alone and send, before the full ledger is built
            val sent = if (Sync.isOn(store)) Notifier.quick(store, fresh).also { Sync.notify(ctx, store, it) } else emptyList()
            val msgs = Notifier.announce(ctx, store, fresh.map { it.id })
            val tags = sent.map { it.tag }.toHashSet()
            Sync.notify(ctx, store, msgs.filter { it.tag !in tags })
            Notifier.check(ctx, store)
        }
        MainActivity.refreshIfOpen()
        Sync.pushIfOn(ctx, store)
    }

    /** The last days of the inbox, through [deliver]: catches what the SMS broadcast missed. Blocking. */
    fun catchUp(ctx: Context, via: String = "box") {
        if (!hasPermission(ctx)) return
        val store = Store(ctx)
        if (store.getLong("fullScanV") < FULL_SCAN_VERSION) { scanInbox(ctx); return }
        val startedAt = System.currentTimeMillis()
        deliver(ctx, readInbox(ctx, (store.getLong("lastScan") - 3L * 24 * 3600 * 1000).coerceAtLeast(0L)), via)
        store.putLong("lastScan", startedAt)
    }

    /**
     * Which SIM a message came on. The subscription id is used first because both the inbox
     * and new messages report it the same way; the slot number only when there is no id.
     * The app shows them as line 1 / line 2 by their order.
     */
    fun simKey(ctx: Context, slot: Int, sub: Int): String {
        if (sub >= 0) return "s$sub"
        if (slot in 0..3) return (slot + 1).toString()
        return ""
    }
}

/** Catches a new SMS the moment it arrives, even when the app is closed. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchService.saw("rcv")
        val ins = wallet(split(context, intent))
        if (ins.isEmpty()) return
        val pending = goAsync()
        Thread {
            try { SmsSource.deliver(context, ins.map { it.raw() }, "rcv", lagOf(ins)) } catch (_: Exception) { } finally { pending.finish() }
        }.start()
        WatchService.start(context)   // the receiver ran but the watcher was stopped: start it again
    }

    /** One SMS as it arrived: the sender, the whole text, when the network sent it, and on which SIM. */
    class In(val sender: String, val body: String, val sentAt: Long, val sim: String, val sub: Int, val pid: Int, val center: String?) {
        fun raw() = RawSms(Store.idOf(sender, body), sender, body, System.currentTimeMillis(), sim)
    }

    companion object {
    fun wallet(list: List<In>) = list.filter { SmsSource.isWallet(it.sender, it.body) }

    /** The wallet SMS in a new-SMS broadcast. */
    fun parse(context: Context, intent: Intent): List<RawSms> = wallet(split(context, intent)).map { it.raw() }

    /** How long after the network sent it (its SMS-center time), or -1 if that time looks wrong. For the server log. */
    fun lagOf(list: List<In>): Long {
        if (list.isEmpty()) return -1
        val now = System.currentTimeMillis()
        val t = list.minOf { it.sentAt }
        return if (t in (now - 86_400_000L)..(now + 300_000L)) (now - t).coerceAtLeast(0) else -1
    }

    /** Every SMS in a new-SMS broadcast (SMS_RECEIVED, or SMS_DELIVER when محافظي is the SMS app), parts joined. */
    fun split(context: Context, intent: Intent): List<In> {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION && intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return emptyList()
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null } ?: return emptyList()
        val ex = intent.extras
        fun intExtra(vararg keys: String): Int {
            for (k in keys) if (ex != null && ex.containsKey(k)) {
                val v = ex.get(k)
                if (v is Int) return v
                if (v is Long) return v.toInt()
                if (v is String) v.toIntOrNull()?.let { return it }
            }
            return -1
        }
        val slot = intExtra("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "slot_id", "simId", "phone")
        val sub = intExtra("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription", "sub_id")
        val sim = SmsSource.simKey(context, slot, sub)
        val bySender = LinkedHashMap<String, Pair<StringBuilder, android.telephony.SmsMessage>>()
        for (m in parts) {
            if (m == null) continue
            val s = m.originatingAddress ?: m.displayOriginatingAddress ?: continue
            val e = bySender.getOrPut(s) { Pair(StringBuilder(), m) }
            e.first.append(m.messageBody ?: "")
        }
        return bySender.map { (s, v) ->
            val first = v.second
            In(s, v.first.toString(), first.timestampMillis, sim, sub,
                try { first.protocolIdentifier } catch (_: Exception) { 0 }, try { first.serviceCenterAddress } catch (_: Exception) { null })
        }
    }
    }
}
