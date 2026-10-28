package com.flashon.wallet

import android.content.Context
import android.os.PowerManager
import com.flashon.wallet.core.Ledger
import com.flashon.wallet.core.MonthSummary
import com.flashon.wallet.core.Parsed
import com.flashon.wallet.core.Wallet
import org.json.JSONArray
import org.json.JSONObject

/** Turns the ledger into the JSON the screens draw. */
object DataJson {
    private fun d(v: Double?): Any = v ?: JSONObject.NULL

    fun build(ctx: Context, store: Store): String {
        val led: Ledger = store.ledger()
        val lim = store.limits()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val o = JSONObject()
        o.put("version", Updater.currentName(ctx))
        o.put("versionCode", Updater.currentCode(ctx))
        o.put("permission", SmsSource.hasPermission(ctx))
        o.put("batteryOk", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        o.put("settings", JSONObject()
            .put("monthReceive", lim.monthReceive).put("monthSend", lim.monthSend)
            .put("daySend", lim.daySend).put("dayReceiveOn", lim.dayReceiveOn)
            .put("dayReceive", lim.dayReceive).put("feesCount", lim.feesCount))
        o.put("update", Updater.cached(ctx))

        val now = JSONArray()
        for (w in Wallet.values()) {
            val n = led.now(w)
            now.put(JSONObject()
                .put("w", w.key).put("label", w.label).put("hasData", n.hasData)
                .put("balance", d(n.balance)).put("lastTime", n.lastTime ?: JSONObject.NULL)
                .put("monthReceived", n.monthReceived).put("monthSent", n.monthSent)
                .put("daySent", n.daySent).put("dayReceived", n.dayReceived)
                .put("receiveLeft", n.receiveLeft).put("sendLeftMonth", n.sendLeftMonth)
                .put("sendLeftDay", n.sendLeftDay).put("receiveLeftDay", d(n.receiveLeftDay))
                .put("canSendNow", n.canSendNow).put("locked", n.lockedNextMonth))
        }
        o.put("now", now)

        val months = led.months()
        o.put("months", JSONArray(months.map { it.toString() }))
        val sums = JSONObject()
        for (m in months) {
            val mo = JSONObject()
            for (w in Wallet.values()) mo.put(w.key, summary(led.summary(m, w)))
            sums.put(m.toString(), mo)
        }
        o.put("summaries", sums)

        val txs = JSONArray()
        for (p in led.txs) txs.put(tx(led, p))
        o.put("txs", txs)
        val unk = JSONArray()
        for (p in led.unknown) unk.put(tx(led, p))
        o.put("unknown", unk)
        return o.toString()
    }

    private fun summary(s: MonthSummary) = JSONObject()
        .put("inTotal", s.inTotal).put("outTotal", s.outTotal).put("fees", s.fees).put("count", s.count)
        .put("start", d(s.startBalance)).put("end", d(s.endBalance))
        .put("received", s.received).put("sent", s.sent)

    private fun tx(led: Ledger, p: Parsed) = JSONObject()
        .put("id", p.sms.id).put("w", p.wallet.key).put("type", p.type.key).put("kind", p.kind)
        .put("amount", d(p.amount)).put("fee", p.fee).put("balance", d(p.balance))
        .put("phone", p.phone ?: JSONObject.NULL).put("name", led.displayName(p) ?: JSONObject.NULL)
        .put("smsName", p.smsName ?: JSONObject.NULL).put("time", p.sms.time)
        .put("how", p.how).put("body", p.sms.body)
}
