package com.flashon.wallet

import android.content.Context
import android.os.PowerManager
import com.flashon.wallet.core.Ledger
import com.flashon.wallet.core.MonthSummary
import com.flashon.wallet.core.Parsed
import com.flashon.wallet.core.Parser
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
        o.put("txNotify", store.txNotify)

        // wallet setup: what the user chose per SIM line, and what the messages show
        val lines = store.lines()
        val lo = JSONObject(); for ((k, w) in lines) lo.put(k, w.key)
        o.put("lines", lo)
        val seen = JSONObject()
        for (r in store.allSms()) {
            val w = Parser.walletOf(r.sender, Parser.normalize(r.body)) ?: continue
            val k = r.sim.ifBlank { "?" }
            val arr = seen.optJSONArray(k) ?: JSONArray().also { seen.put(k, it) }
            var has = false
            for (i in 0 until arr.length()) if (arr.getString(i) == w.key) has = true
            if (!has) arr.put(w.key)
        }
        o.put("seen", seen)

        val now = JSONArray()
        for (acc in led.accounts) {
            val n = led.now(acc)
            val al = led.limitsFor(acc)
            now.put(JSONObject()
                .put("line", acc.line)
                .put("limits", JSONObject().put("monthReceive", al.monthReceive).put("monthSend", al.monthSend)
                    .put("daySend", al.daySend).put("own", led.accountLimits.containsKey(acc.key)))
                .put("w", acc.key).put("p", acc.wallet.key).put("label", led.accountLabel(acc))
                .put("custom", led.learned.accountNames.containsKey(acc.key))
                .put("own", n.ownNumber ?: JSONObject.NULL).put("hasData", n.hasData)
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
            for (acc in led.accounts) mo.put(acc.key, summary(led.summary(m, acc)))
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
        .put("id", p.sms.id).put("w", led.account(p).key).put("p", p.wallet.key).put("type", p.type.key).put("kind", p.kind)
        .put("amount", d(p.amount)).put("fee", p.fee).put("balance", d(p.balance))
        .put("phone", p.phone ?: JSONObject.NULL).put("name", led.displayName(p) ?: JSONObject.NULL)
        .put("smsName", p.smsName ?: JSONObject.NULL).put("time", p.sms.time)
        .put("how", p.how).put("body", p.sms.body)
}
