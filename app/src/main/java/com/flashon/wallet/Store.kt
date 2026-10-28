package com.flashon.wallet

import android.content.Context
import com.flashon.wallet.core.Learned
import com.flashon.wallet.core.Ledger
import com.flashon.wallet.core.Limits
import com.flashon.wallet.core.Parser
import com.flashon.wallet.core.RawSms
import com.flashon.wallet.core.TxType
import com.flashon.wallet.core.Wallet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Everything the app keeps, all on the phone:
 *  - a copy of every wallet SMS (so nothing is lost if the user deletes messages)
 *  - what the user taught it (types, names), settings, notification memory.
 */
class Store(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("wallet", Context.MODE_PRIVATE)
    private val smsFile = File(ctx.filesDir, "sms.json")

    companion object {
        private val LOCK = Any()

        fun idOf(sender: String, body: String): String {
            val key = sender.trim().lowercase() + "|" + Parser.normalize(body)
            val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            return d.take(10).joinToString("") { "%02x".format(it) }
        }
    }

    // ---------- messages ----------
    fun allSms(): List<RawSms> = synchronized(LOCK) { readSms().values.toList() }

    private fun readSms(): LinkedHashMap<String, RawSms> {
        val map = LinkedHashMap<String, RawSms>()
        if (!smsFile.exists()) return map
        try {
            val arr = JSONArray(smsFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = RawSms(o.getString("id"), o.getString("s"), o.getString("b"), o.getLong("t"), o.optString("m", ""))
                map[r.id] = r
            }
        } catch (_: Exception) { }
        return map
    }

    /**
     * Adds messages that are not saved yet. Returns how many were new.
     * trustSim: the phone's inbox is the reference for which SIM a message came on.
     */
    fun addSms(list: List<RawSms>, trustSim: Boolean = false): Int = synchronized(LOCK) {
        val map = readSms()
        var added = 0
        var changed = false
        for (r in list) {
            val e = map[r.id]
            if (e == null) { map[r.id] = r; added++; changed = true; continue }
            val sim = if (r.sim.isNotBlank() && (e.sim.isBlank() || trustSim)) r.sim else e.sim
            val time = minOf(e.time, r.time)
            if (sim != e.sim || time != e.time) { map[r.id] = e.copy(sim = sim, time = time); changed = true }
        }
        if (changed) {
            val arr = JSONArray()
            for (r in map.values) arr.put(JSONObject().put("id", r.id).put("s", r.sender).put("b", r.body).put("t", r.time).put("m", r.sim))
            val tmp = File(ctx.filesDir, "sms.json.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            tmp.renameTo(smsFile)
        }
        added
    }

    // ---------- settings ----------
    fun limits(): Limits = Limits(
        monthReceive = prefs.getFloat("monthReceive", 200000f).toDouble(),
        monthSend = prefs.getFloat("monthSend", 200000f).toDouble(),
        daySend = prefs.getFloat("daySend", 60000f).toDouble(),
        dayReceiveOn = prefs.getBoolean("dayReceiveOn", false),
        dayReceive = prefs.getFloat("dayReceive", 60000f).toDouble(),
        feesCount = prefs.getBoolean("feesCount", true)
    )

    fun saveLimits(l: Limits) {
        prefs.edit()
            .putFloat("monthReceive", l.monthReceive.toFloat())
            .putFloat("monthSend", l.monthSend.toFloat())
            .putFloat("daySend", l.daySend.toFloat())
            .putBoolean("dayReceiveOn", l.dayReceiveOn)
            .putFloat("dayReceive", l.dayReceive.toFloat())
            .putBoolean("feesCount", l.feesCount)
            .apply()
    }

    // ---------- what the user taught ----------
    private fun obj(key: String): JSONObject = try { JSONObject(prefs.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun typeMap(key: String): Map<String, TxType> {
        val o = obj(key); val m = HashMap<String, TxType>()
        for (k in o.keys()) TxType.fromKey(o.optString(k))?.let { m[k] = it }
        return m
    }

    private fun strMap(key: String): Map<String, String> {
        val o = obj(key); val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k)
        return m
    }

    fun learned() = Learned(typeMap("overrides"), typeMap("rules"), strMap("names"), strMap("accnames"))

    fun setOverride(id: String, t: TxType) = synchronized(LOCK) {
        prefs.edit().putString("overrides", obj("overrides").put(id, t.key).toString()).apply()
    }

    fun setRule(signature: String, t: TxType) = synchronized(LOCK) {
        prefs.edit().putString("rules", obj("rules").put(signature, t.key).toString()).apply()
    }

    fun setName(phone: String, name: String) = synchronized(LOCK) {
        val o = obj("names")
        if (name.isBlank()) o.remove(phone) else o.put(phone, name.take(40))
        prefs.edit().putString("names", o.toString()).apply()
    }

    fun setAccountName(key: String, name: String) = synchronized(LOCK) {
        val o = obj("accnames")
        if (name.isBlank()) o.remove(key) else o.put(key, name.take(30))
        prefs.edit().putString("accnames", o.toString()).apply()
    }

    // ---------- which wallet is on which SIM line (empty = automatic) ----------
    fun lines(): Map<String, Wallet> {
        val o = obj("lines"); val m = LinkedHashMap<String, Wallet>()
        for (k in o.keys()) Wallet.fromKey(o.optString(k))?.let { m[k] = it }
        return m
    }

    /** null = back to automatic */
    fun setLines(m: Map<String, String>?) = synchronized(LOCK) {
        if (m == null) { prefs.edit().remove("lines").apply(); return@synchronized }
        val o = JSONObject()
        for ((k, v) in m) if (Wallet.fromKey(v) != null) o.put(k, v)
        prefs.edit().putString("lines", o.toString()).apply()
    }

    // ---------- limits for one wallet ----------
    fun accountLimits(): Map<String, Limits> {
        val o = obj("acclimits"); val m = HashMap<String, Limits>()
        val base = limits()
        for (k in o.keys()) {
            val j = o.optJSONObject(k) ?: continue
            m[k] = base.copy(
                monthReceive = j.optDouble("monthReceive", base.monthReceive),
                monthSend = j.optDouble("monthSend", base.monthSend),
                daySend = j.optDouble("daySend", base.daySend)
            )
        }
        return m
    }

    /** null = use the normal limits again */
    fun setAccountLimits(key: String, j: JSONObject?) = synchronized(LOCK) {
        val o = obj("acclimits")
        if (j == null) o.remove(key) else o.put(key, j)
        prefs.edit().putString("acclimits", o.toString()).apply()
    }

    var txNotify: Boolean
        get() = prefs.getBoolean("txNotify", true)
        set(v) { prefs.edit().putBoolean("txNotify", v).apply() }

    fun ledger(): Ledger = Ledger(
        allSms(), limits(), learned(),
        lines = lines(), accountLimits = accountLimits()
    )

    // ---------- small memory (notifications, updates) ----------
    fun getStr(k: String): String? = prefs.getString(k, null)
    fun putStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun getLong(k: String): Long = prefs.getLong(k, 0L)
    fun putLong(k: String, v: Long) = prefs.edit().putLong(k, v).apply()
    fun getDouble(k: String): Double = prefs.getFloat(k, -1f).toDouble()
    fun putDouble(k: String, v: Double) = prefs.edit().putFloat(k, v.toFloat()).apply()
}
