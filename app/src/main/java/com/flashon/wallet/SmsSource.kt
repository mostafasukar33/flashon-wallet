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
    fun scanInbox(ctx: Context): Int {
        if (!hasPermission(ctx)) return 0
        val found = ArrayList<RawSms>()
        try {
            // all columns, because the SIM column name differs between phones (Samsung: sim_slot)
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, null, null, null, Telephony.Sms.DATE + " ASC"
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
        return Store(ctx).addSms(found, trustSim = true)
    }

    /** "1" / "2" for SIM slot 1 / 2 when known, otherwise "s<subscription>" or "". */
    fun simKey(ctx: Context, slot: Int, sub: Int): String {
        if (slot in 0..3) return (slot + 1).toString()
        if (sub >= 0) {
            val fromSub = slotOfSub(ctx, sub)
            if (fromSub in 0..3) return (fromSub + 1).toString()
            return "s$sub"
        }
        return ""
    }

    private fun slotOfSub(ctx: Context, sub: Int): Int = try {
        val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
        sm?.getActiveSubscriptionInfo(sub)?.simSlotIndex ?: -1
    } catch (_: Exception) { -1 }
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
