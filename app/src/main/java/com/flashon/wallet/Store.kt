package com.flashon.wallet

import android.content.Context
import com.flashon.wallet.core.Learned
import com.flashon.wallet.core.Ledger
import com.flashon.wallet.core.Limits
import com.flashon.wallet.core.Parser
import com.flashon.wallet.core.RawSms
import com.flashon.wallet.core.TxType
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
                val r = RawSms(o.getString("id"), o.getString("s"), o.getString("b"), o.getLong("t"))
                map[r.id] = r
            }
        } catch (_: Exception) { }
        return map
    }

    /** Adds messages that are not saved yet. Returns how many were new. */
    fun addSms(list: List<RawSms>): Int = synchronized(LOCK) {
        val map = readSms()
        var added = 0
        for (r in list) {
            val existing = map[r.id]
            if (existing == null) { map[r.id] = r; added++ }
            else if (r.time < existing.time) map[r.id] = r // keep the earliest time we saw
        }
        if (added > 0 || list.isNotEmpty()) {
            val arr = JSONArray()
            for (r in map.values) arr.put(JSONObject().put("id", r.id).put("s", r.sender).put("b", r.body).put("t", r.time))
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

    fun learned() = Learned(typeMap("overrides"), typeMap("rules"), strMap("names"))

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

    fun ledger(): Ledger = Ledger(allSms(), limits(), learned())

    // ---------- small memory (notifications, updates) ----------
    fun getStr(k: String): String? = prefs.getString(k, null)
    fun putStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun getLong(k: String): Long = prefs.getLong(k, 0L)
    fun putLong(k: String, v: Long) = prefs.edit().putLong(k, v).apply()
    fun getDouble(k: String): Double = prefs.getFloat(k, -1f).toDouble()
    fun putDouble(k: String, v: Double) = prefs.edit().putFloat(k, v.toFloat()).apply()
}
