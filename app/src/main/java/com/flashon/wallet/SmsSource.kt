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
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, Telephony.Sms.DATE + " ASC"
            )?.use { c ->
                val ia = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val ib = c.getColumnIndex(Telephony.Sms.BODY)
                val id = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val sender = c.getString(ia) ?: continue
                    val body = c.getString(ib) ?: continue
                    if (!isWallet(sender, body)) continue
                    found += RawSms(Store.idOf(sender, body), sender, body, c.getLong(id))
                }
            }
        } catch (_: Exception) { }
        return Store(ctx).addSms(found)
    }
}

/** Catches a new SMS the moment it arrives, even when the app is closed. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null } ?: return
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
            else RawSms(Store.idOf(s, body), s, body, System.currentTimeMillis())
        }
        if (list.isEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                val store = Store(context)
                if (store.addSms(list) > 0) {
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
