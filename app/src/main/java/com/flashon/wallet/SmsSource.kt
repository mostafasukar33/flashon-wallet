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
        val added = store.addSms(found, trustSim = true)
        store.putLong("lastScan", startedAt)
        if (doFull) store.putLong("fullScanV", FULL_SCAN_VERSION.toLong())
        return added
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
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null } ?: return
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
        val bySender = LinkedHashMap<String, Pair<StringBuilder, Long>>()
        for (m in parts) {
            if (m == null) continue
            val s = m.originatingAddress ?: m.displayOriginatingAddress ?: continue
            val e = bySender.getOrPut(s) { Pair(StringBuilder(), m.timestampMillis) }
            e.first.append(m.messageBody ?: "")
        }
        val list = bySender.mapNotNull { (s, v) ->
            val body = v.first.toString()
            if (!SmsSource.isWallet(s, body)) null
            else RawSms(Store.idOf(s, body), s, body, System.currentTimeMillis(), sim)
        }
        if (list.isEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                val store = Store(context)
                val before = store.allSms().map { it.id }.toHashSet()
                if (store.addSms(list) > 0) {
                    Notifier.announce(context, store, list.map { it.id }.filter { it !in before })
                    Notifier.check(context, store)
                    MainActivity.refreshIfOpen()
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
