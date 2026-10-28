package com.flashon.wallet.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/** Limits as the user set them. Same numbers for both wallets. */
data class Limits(
    val monthReceive: Double = 200_000.0,
    val monthSend: Double = 200_000.0,
    val daySend: Double = 60_000.0,
    val dayReceiveOn: Boolean = false,
    val dayReceive: Double = 60_000.0,
    val feesCount: Boolean = true
)

/** What the user taught the app. */
data class Learned(
    val overrides: Map<String, TxType> = emptyMap(),   // sms id -> type
    val rules: Map<String, TxType> = emptyMap(),       // message signature -> type
    val names: Map<String, String> = emptyMap()        // phone -> name the user chose
)

data class MonthSummary(
    var inTotal: Double = 0.0,
    var outTotal: Double = 0.0,
    var fees: Double = 0.0,
    var count: Int = 0,
    var startBalance: Double? = null,
    var endBalance: Double? = null,
    var received: Double = 0.0, // counted toward the receive limit
    var sent: Double = 0.0      // counted toward the send limit (fees included if set)
)

data class WalletNow(
    val wallet: Wallet,
    val hasData: Boolean,
    val balance: Double?,
    val lastTime: Long?,
    val monthReceived: Double,
    val monthSent: Double,
    val daySent: Double,
    val dayReceived: Double,
    val receiveLeft: Double,
    val sendLeftMonth: Double,
    val sendLeftDay: Double,
    val receiveLeftDay: Double?,
    val canSendNow: Double,
    val lockedNextMonth: Double
)

class Ledger(
    raws: List<RawSms>,
    val limits: Limits,
    val learned: Learned,
    val zone: ZoneId = ZoneId.systemDefault(),
    val nowMillis: Long = System.currentTimeMillis()
) {
    /** every wallet message we understood or not, oldest first */
    val all: List<Parsed>
    val txs: List<Parsed>
    val unknown: List<Parsed>

    init {
        val parsed = raws.mapNotNull { Parser.parse(it) }
            .distinctBy { it.txId?.let { id -> it.wallet.key + id } ?: ("sms" + it.sms.id) }
            .sortedBy { it.sms.time }

        // 1) the user's word comes first, 2) learned message shapes, 3) balance arithmetic
        val lastBalance = HashMap<Wallet, Double>()
        for (p in parsed) {
            val o = learned.overrides[p.sms.id]
            if (o != null) {
                p.type = o; p.how = "override"
            } else if (p.type == TxType.UNKNOWN) {
                val r = learned.rules[p.signature]
                if (r != null) {
                    p.type = r; p.how = "rule"
                } else {
                    val prev = lastBalance[p.wallet]
                    val a = p.amount
                    val b = p.balance
                    if (prev != null && a != null && b != null) {
                        if (kotlin.math.abs(prev + a - b) < 0.011) { p.type = TxType.IN; p.how = "balance" }
                        else if (kotlin.math.abs(prev - a - p.fee - b) < 0.011) { p.type = TxType.OUT; p.how = "balance" }
                    }
                }
            }
            if (p.kind.isBlank() || p.how == "override" || p.how == "rule" || p.how == "balance") {
                if (p.kind.isBlank()) p.kind = when (p.type) { TxType.IN -> "وارد"; TxType.OUT -> "صادر"; else -> "" }
            }
            p.balance?.let { lastBalance[p.wallet] = it }
        }
        all = parsed
        txs = parsed.filter { it.type == TxType.IN || it.type == TxType.OUT }
        unknown = parsed.filter { it.type == TxType.UNKNOWN }
    }

    fun monthOf(t: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(t).atZone(zone))
    fun dayOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    private fun sentCost(p: Parsed) = (p.amount ?: 0.0) + if (limits.feesCount) p.fee else 0.0

    fun displayName(p: Parsed): String? =
        p.phone?.let { learned.names[it] } ?: p.smsName ?: p.phone

    fun months(): List<YearMonth> =
        (txs.map { monthOf(it.sms.time) } + monthOf(nowMillis)).distinct().sortedDescending()

    fun summary(month: YearMonth, wallet: Wallet): MonthSummary {
        val s = MonthSummary()
        var lastBefore: Double? = null
        for (p in all) {
            if (p.wallet != wallet) continue
            val m = monthOf(p.sms.time)
            if (m < month) { p.balance?.let { lastBefore = it }; continue }
            if (m > month) break
            if (p.type == TxType.IN) {
                s.inTotal += p.amount ?: 0.0; s.received += p.amount ?: 0.0; s.count++
                if (s.startBalance == null && lastBefore == null && p.balance != null) s.startBalance = p.balance - (p.amount ?: 0.0)
            } else if (p.type == TxType.OUT) {
                s.outTotal += p.amount ?: 0.0; s.fees += p.fee; s.sent += sentCost(p); s.count++
                if (s.startBalance == null && lastBefore == null && p.balance != null) s.startBalance = p.balance + (p.amount ?: 0.0) + p.fee
            }
            p.balance?.let { s.endBalance = it }
        }
        if (lastBefore != null) s.startBalance = lastBefore
        if (s.endBalance == null) s.endBalance = lastBefore
        return s
    }

    fun now(wallet: Wallet): WalletNow {
        val thisMonth = monthOf(nowMillis)
        val today = dayOf(nowMillis)
        val mine = all.filter { it.wallet == wallet }
        val balance = mine.lastOrNull { it.balance != null }?.balance
        var mr = 0.0; var ms = 0.0; var ds = 0.0; var dr = 0.0
        for (p in txs) {
            if (p.wallet != wallet) continue
            if (monthOf(p.sms.time) != thisMonth) continue
            val isToday = dayOf(p.sms.time) == today
            if (p.type == TxType.IN) { mr += p.amount ?: 0.0; if (isToday) dr += p.amount ?: 0.0 }
            else { ms += sentCost(p); if (isToday) ds += sentCost(p) }
        }
        val receiveLeft = (limits.monthReceive - mr).coerceAtLeast(0.0)
        val sendLeftMonth = (limits.monthSend - ms).coerceAtLeast(0.0)
        val sendLeftDay = (limits.daySend - ds).coerceAtLeast(0.0)
        val receiveLeftDay = if (limits.dayReceiveOn) (limits.dayReceive - dr).coerceAtLeast(0.0) else null
        var canSend = minOf(sendLeftMonth, sendLeftDay)
        if (balance != null) canSend = minOf(canSend, balance.coerceAtLeast(0.0))
        val locked = if (balance != null) (balance - minOf(balance, sendLeftMonth)).coerceAtLeast(0.0) else 0.0
        return WalletNow(
            wallet, mine.isNotEmpty(), balance, mine.lastOrNull()?.sms?.time,
            mr, ms, ds, dr, receiveLeft, sendLeftMonth, sendLeftDay, receiveLeftDay, canSend, locked
        )
    }

    /** money left over at the end of a past month because the send limit was reached */
    fun lockedAtEndOf(month: YearMonth, wallet: Wallet): Double {
        val s = summary(month, wallet)
        val end = s.endBalance ?: return 0.0
        val left = (limits.monthSend - s.sent).coerceAtLeast(0.0)
        return (end - minOf(end, left)).coerceAtLeast(0.0)
    }
}
