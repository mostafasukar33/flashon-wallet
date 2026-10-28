package com.flashon.wallet.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/** Limits as the user set them. Same numbers for every wallet. */
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
    val names: Map<String, String> = emptyMap(),       // phone -> name the user chose
    val accountNames: Map<String, String> = emptyMap() // account key -> name the user chose
)

/**
 * One wallet the user owns: a provider on a SIM line.
 * A phone with two Vodafone SIMs has two accounts: vf1 and vf2.
 */
data class Account(val wallet: Wallet, val line: String) {
    val key: String get() = wallet.key + line
}

data class MonthSummary(
    var inTotal: Double = 0.0,
    var outTotal: Double = 0.0,
    var fees: Double = 0.0,
    var count: Int = 0,
    var startBalance: Double? = null,
    var endBalance: Double? = null,
    var received: Double = 0.0,
    var sent: Double = 0.0
)

data class WalletNow(
    val account: Account,
    val hasData: Boolean,
    val balance: Double?,
    val lastTime: Long?,
    val ownNumber: String?,
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

/**
 * lines: what the user set for each SIM line ("1" -> VODAFONE, "2" -> ETISALAT).
 *        Empty = work it out from the messages.
 * accountLimits: limits the user set for one wallet (account key -> Limits); others use [limits].
 */
/** "1" < "2" < "s3" < "s10": by the number inside, then by text */
val LINE_ORDER: Comparator<String> = compareBy<String>({ it.startsWith("s") }, { it.filter { c -> c.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE }, { it })

class Ledger(
    private val raws: List<RawSms>,
    val limits: Limits,
    val learned: Learned,
    val zone: ZoneId = ZoneId.systemDefault(),
    val nowMillis: Long = System.currentTimeMillis(),
    val lines: Map<String, Wallet> = emptyMap(),
    val accountLimits: Map<String, Limits> = emptyMap()
) {
    val all: List<Parsed>
    val txs: List<Parsed>
    val unknown: List<Parsed>
    /** accounts that have messages, plus an empty one per provider that has none (to show the card) */
    val accounts: List<Account>
    private val accountOf = HashMap<String, Account>() // sms id -> account
    /** true when a provider is split over more than one SIM line */
    private val multiLine = HashSet<Wallet>()

    init {
        var parsed = raws.mapNotNull { Parser.parse(it) }
            .distinctBy { it.txId?.let { id -> it.wallet.key + id } ?: ("sms" + it.sms.id) }
            .sortedBy { it.sms.time }

        val acc = ArrayList<Account>()
        // SIM lines seen in the messages, in a stable order (line 1 first)
        val seenLines = parsed.map { it.sms.sim }.filter { it.isNotBlank() }.distinct().sortedWith(LINE_ORDER)
        if (lines.isNotEmpty()) {
            // The user said which wallet is on which line. A key like "2" that no message uses
            // means "the 2nd line the phone reports".
            fun real(k: String): String =
                if (k in seenLines) k
                else k.toIntOrNull()?.let { n -> seenLines.getOrNull(n - 1) } ?: k
            for ((line, w) in lines.entries.sortedWith(compareBy(LINE_ORDER) { it.key })) {
                val a = Account(w, real(line))
                if (acc.none { it.key == a.key }) acc += a
            }
            val keep = ArrayList<Parsed>()
            for (p in parsed) {
                val a = acc.firstOrNull { it.wallet == p.wallet && it.line == p.sms.sim }
                    ?: (if (p.sms.sim.isBlank()) acc.firstOrNull { it.wallet == p.wallet } else null)
                    ?: continue // a wallet / line the user said isn't used for this
                accountOf[p.sms.id] = a
                keep += p
            }
            parsed = keep
            for (a in acc) if (acc.count { it.wallet == a.wallet } > 1) multiLine += a.wallet
        } else {
            val any = parsed.isNotEmpty()
            for (w in Wallet.values()) {
                val mine = parsed.filter { it.wallet == w }
                if (mine.isEmpty() && any) continue // don't show a wallet that never sent a message
                val ls = mine.map { it.sms.sim }.filter { it.isNotBlank() }.distinct().sortedWith(LINE_ORDER)
                if (ls.size >= 2) {
                    multiLine += w
                    for (l in ls) acc += Account(w, l)
                    for (p in mine) accountOf[p.sms.id] = Account(w, p.sms.sim.ifBlank { ls.first() })
                } else {
                    val a = Account(w, "")
                    acc += a
                    for (p in mine) accountOf[p.sms.id] = a
                }
            }
        }
        accounts = acc

        // 1) the user's word, 2) learned message shapes, 3) balance arithmetic
        val lastBalance = HashMap<String, Double>()
        for (p in parsed) {
            val ak = account(p).key
            val o = learned.overrides[p.sms.id]
            if (o != null) {
                p.type = o; p.how = "override"
            } else if (p.type == TxType.UNKNOWN) {
                val r = learned.rules[p.signature]
                if (r != null) {
                    p.type = r; p.how = "rule"
                } else {
                    val prev = lastBalance[ak]
                    val a = p.amount
                    val b = p.balance
                    if (prev != null && a != null && b != null) {
                        if (kotlin.math.abs(prev + a - b) < 0.011) { p.type = TxType.IN; p.how = "balance" }
                        else if (kotlin.math.abs(prev - a - p.fee - b) < 0.011) { p.type = TxType.OUT; p.how = "balance" }
                    }
                }
            }
            if (p.kind.isBlank()) p.kind = when (p.type) { TxType.IN -> "وارد"; TxType.OUT -> "صادر"; else -> "" }
            p.balance?.let { lastBalance[ak] = it }
        }
        all = parsed
        txs = parsed.filter { it.type == TxType.IN || it.type == TxType.OUT }
        unknown = parsed.filter { it.type == TxType.UNKNOWN }
    }

    /** all SIM lines the messages came on, line 1 first */
    val phoneLines: List<String> by lazy {
        raws.map { it.sim }.filter { it.isNotBlank() }.distinct().sortedWith(LINE_ORDER)
    }

    /** "1", "2"... as the user would count the SIMs */
    fun lineNumber(line: String): String {
        val i = phoneLines.indexOf(line)
        return if (i >= 0) (i + 1).toString() else line.filter { it.isDigit() }.ifBlank { "?" }
    }

    fun limitsFor(a: Account): Limits = accountLimits[a.key] ?: limits

    fun account(p: Parsed): Account = accountOf[p.sms.id] ?: Account(p.wallet, "")

    fun accountLabel(a: Account): String {
        learned.accountNames[a.key]?.takeIf { it.isNotBlank() }?.let { return it }
        if (a.wallet !in multiLine) return a.wallet.label
        return a.wallet.label + " – خط " + lineNumber(a.line)
    }

    fun monthOf(t: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(t).atZone(zone))
    fun dayOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    private fun sentCost(p: Parsed) = (p.amount ?: 0.0) + if (limitsFor(account(p)).feesCount) p.fee else 0.0

    fun displayName(p: Parsed): String? =
        p.phone?.let { learned.names[it] } ?: p.smsName ?: p.phone

    fun months(): List<YearMonth> =
        (txs.map { monthOf(it.sms.time) } + monthOf(nowMillis)).distinct().sortedDescending()

    fun summary(month: YearMonth, a: Account): MonthSummary {
        val s = MonthSummary()
        var lastBefore: Double? = null
        for (p in all) {
            if (account(p) != a) continue
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

    fun now(a: Account): WalletNow {
        val limits = limitsFor(a)
        val thisMonth = monthOf(nowMillis)
        val today = dayOf(nowMillis)
        val mine = all.filter { account(it) == a }
        val balance = mine.lastOrNull { it.balance != null }?.balance
        val own = mine.lastOrNull { it.own != null }?.own
        var mr = 0.0; var ms = 0.0; var ds = 0.0; var dr = 0.0
        for (p in txs) {
            if (account(p) != a) continue
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
            a, mine.isNotEmpty(), balance, mine.lastOrNull()?.sms?.time, own,
            mr, ms, ds, dr, receiveLeft, sendLeftMonth, sendLeftDay, receiveLeftDay, canSend, locked
        )
    }

    fun lockedAtEndOf(month: YearMonth, a: Account): Double {
        val limits = limitsFor(a)
        val s = summary(month, a)
        val end = s.endBalance ?: return 0.0
        val left = (limits.monthSend - s.sent).coerceAtLeast(0.0)
        return (end - minOf(end, left)).coerceAtLeast(0.0)
    }
}
