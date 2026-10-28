package com.flashon.wallet.core

/**
 * Reads one wallet SMS (Vodafone Cash / Etisalat Cash) and pulls out what it means.
 * It does not depend on a fixed message layout: it looks for words and numbers
 * the way a person would (amount near "مبلغ" or "جنيه", balance near "رصيد", ...).
 * Pure Kotlin, no Android imports, so it can be tested on any machine.
 */

enum class Wallet(val key: String, val label: String) {
    VODAFONE("vf", "فودافون كاش"),
    ETISALAT("et", "اتصالات كاش");

    companion object {
        fun fromKey(k: String?): Wallet? = values().firstOrNull { it.key == k }
    }
}

enum class TxType(val key: String) {
    IN("in"), OUT("out"), IGNORE("ignore"), UNKNOWN("unknown");

    companion object {
        fun fromKey(k: String?): TxType? = values().firstOrNull { it.key == k }
    }
}

/** sim = which SIM line the message came on ("1", "2", or "" when the phone does not say). */
data class RawSms(val id: String, val sender: String, val body: String, val time: Long, val sim: String = "")

data class Parsed(
    val sms: RawSms,
    val wallet: Wallet,
    var type: TxType,
    val amount: Double?,
    val fee: Double,
    val balance: Double?,
    val phone: String?,
    val smsName: String?,
    val txId: String?,
    val signature: String,
    var kind: String,
    val own: String? = null,
    var how: String = "words" // words | rule | override | balance
)

object Parser {

    private const val NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
    private val AMOUNT_RX = listOf(
            "(?:بمبلغ|مبلغ|بقيمة|قيمة|amount(?: of)?)\\s*:?\\s*(?:egp|ج\\.?م)?\\s*$NUM",
            "(?:تحويل|التحويل|استلام|إيداع|ايداع|سحب|دفع|شراء|إضافة|اضافة|خصم|استرداد|سداد|received|sent|paid|transferred|deposited|withdrew)\\s+(?:egp\\s*)?$NUM",
            "$NUM\\s*(?:جنيه|جنية|ج\\.م|ج م|egp|le\\b|l\\.e)",
            "(?:egp|ج\\.م)\\s*$NUM"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val RX1 = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val RX2 = Regex("[\\u200e\\u200f\\u202a-\\u202e]")
    private val RX3 = Regex("\\s+")
    private val RX4 = Regex("(?:رقم العملية|رقم المعاملة|رقم المرجع|transaction id|trx id|ref(?:erence)?(?: no)?)[^\\d]{0,12}(\\d{6,})", RegexOption.IGNORE_CASE)
    private val RX5 = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
    private val RX6 = Regex("\\b\\d{2,4}[-/]\\d{1,2}[-/]\\d{1,4}\\b")
    private val RX7 = Regex("(?:رصيد|balance)[^\\d]{0,45}?$NUM", RegexOption.IGNORE_CASE)
    private val RX8 = Regex("(?:مصاريف|رسوم|عمولة|fees?|charges?)[^\\d]{0,18}?$NUM", RegexOption.IGNORE_CASE)
    private val RX9 = Regex("(?:رقم محفظتك|محفظتك رقم|على محفظتك|علي محفظتك|your wallet(?: number)?)\\s*:?\\s*(01\\d{8,10})", RegexOption.IGNORE_CASE)
    private val RX10 = Regex("(?<!\\d)(?:\\+?2)?(01\\d{8,10})(?!\\d)")
    private val RX11 = Regex("(?:المسجل بإسم|المسجل باسم|المسجل بأسم|بإسم|باسم|بأسم|name)\\s*:?\\s*(.+?)(?=\\s+(?:على|علي|الى|إلى|في|فى|من|on|to)\\s|[.،,:\\n]|\\s+\\d|$)", RegexOption.IGNORE_CASE)
    private val RX12 = Regex("[\\d.,:/\\-+]+")
    private val RX13 = Regex("[^\\p{L}\\s]")

    /** Arabic-Indic digits -> ASCII, Arabic separators -> ASCII, links removed, spaces collapsed. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.'
                    '٬' -> ','
                    '،' -> '،'
                    else -> c
                }
            )
        }
        return sb.toString()
            .replace(RX1, " ")
            .replace(RX2, "")
            .replace(RX3, " ")
            .trim()
    }

    private val NAME_STOP = Regex("\\s*(?:بنجاح|بنجاح\\.|successfully|تم|رصيد).*$", RegexOption.IGNORE_CASE)

    /** "Mohamed G Derar   بنجاح" -> "Mohamed G Derar" */
    fun cleanName(raw: String): String? =
        raw.replace(NAME_STOP, "").replace(Regex("\\s+"), " ").trim().take(40).ifBlank { null }

    /** For word matching only: أ إ آ -> ا , ى -> ي , ة -> ه */
    fun fold(s: String): String = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

    fun toNumber(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    fun walletOf(sender: String, body: String): Wallet? {
        val s = sender.lowercase()
        val b = body.lowercase()
        val looksLikeWallet = b.contains("كاش") || b.contains("محفظ") || b.contains("cash") ||
            b.contains("wallet") || s.contains("cash")
        if (!looksLikeWallet) return null
        if (s.contains("vf") || s.contains("vodafone") || s.contains("فودافون")) return Wallet.VODAFONE
        if (s.contains("etisalat") || s.contains("e&") || s.contains("اتصالات") || s.contains("emoney") || s.contains("e-money"))
            return Wallet.ETISALAT
        if (b.contains("فودافون كاش") || b.contains("vodafone cash")) return Wallet.VODAFONE
        if (b.contains("اتصالات كاش") || b.contains("e& money") || b.contains("etisalat cash") || b.contains("etisalat money") ||
            b.contains("اي آند") || b.contains("إي آند") || b.contains("اتصالات موني") || b.contains("e&money") || b.contains("emoney"))
            return Wallet.ETISALAT
        if (s.contains("eand") || s.contains("e-and") || s.contains("e and")) return Wallet.ETISALAT
        return null
    }

    private val IGNORE_WORDS = listOf(
        "فشل", "لم تتم", "لم يتم", "غير كاف", "تم رفض", "مرفوض", "failed", "declined", "insufficient",
        "كود التحقق", "كود التفعيل", "رمز التحقق", "كلمة السر", "الرقم السري", "otp", "verification",
        "يطلب منك", "طلب منك", "طلب تحويل"
    )

    // (word, type, kind label). Earliest word in the message decides.
    private val TYPE_WORDS: List<Triple<String, TxType, String>> = listOf(
        Triple("تم استلام", TxType.IN, "استلام"),
        Triple("استلمت", TxType.IN, "استلام"),
        Triple("تم إضافة", TxType.IN, "إضافة"),
        Triple("تم اضافة", TxType.IN, "إضافة"),
        Triple("تمت إضافة", TxType.IN, "إضافة"),
        Triple("تمت اضافة", TxType.IN, "إضافة"),
        Triple("تم إيداع", TxType.IN, "إيداع"),
        Triple("تم ايداع", TxType.IN, "إيداع"),
        Triple("إيداع", TxType.IN, "إيداع"),
        Triple("ايداع", TxType.IN, "إيداع"),
        Triple("استرداد", TxType.IN, "استرداد"),
        Triple("كاش باك", TxType.IN, "كاش باك"),
        Triple("cashback", TxType.IN, "كاش باك"),
        Triple("received", TxType.IN, "استلام"),
        Triple("credited", TxType.IN, "إضافة"),
        Triple("deposit", TxType.IN, "إيداع"),
        Triple("تم تحويل", TxType.OUT, "تحويل"),
        Triple("تم التحويل", TxType.OUT, "تحويل"),
        Triple("حولت", TxType.OUT, "تحويل"),
        Triple("تم دفع", TxType.OUT, "دفع"),
        Triple("تم الدفع", TxType.OUT, "دفع"),
        Triple("دفعت", TxType.OUT, "دفع"),
        Triple("تم سحب", TxType.OUT, "سحب"),
        Triple("تم السحب", TxType.OUT, "سحب"),
        Triple("سحب", TxType.OUT, "سحب"),
        Triple("تم شراء", TxType.OUT, "شراء"),
        Triple("تم خصم", TxType.OUT, "خصم"),
        Triple("تم سداد", TxType.OUT, "دفع"),
        Triple("transferred", TxType.OUT, "تحويل"),
        Triple("sent", TxType.OUT, "تحويل"),
        Triple("paid", TxType.OUT, "دفع"),
        Triple("withdraw", TxType.OUT, "سحب"),
        Triple("debited", TxType.OUT, "خصم")
    )

    // "تم تحويل ... الى محفظتك من" means money came TO you.
    private val TO_ME = listOf("الى محفظتك", "إلى محفظتك", "لمحفظتك", "إليك", "اليك", "to your wallet")

    private class Hold(val p: Parsed?)
    private val cache = java.util.concurrent.ConcurrentHashMap<RawSms, Hold>()

    /** Same as [parse] but each message is read only once per app run. Returns a fresh copy. */
    fun parseCached(sms: RawSms): Parsed? {
        val h = cache.getOrPut(sms) { Hold(parse(sms)) }
        return h.p?.copy()
    }

    fun parse(sms: RawSms): Parsed? {
        val text = normalize(sms.body)
        val wallet = walletOf(sms.sender, text) ?: return null
        val low = fold(text.lowercase())

        // Transaction id (taken out so its digits are never read as a phone or an amount)
        var work = text
        var txId: String? = null
        RX4
            .find(work)?.let { m ->
                txId = m.groupValues[1]
                work = work.replaceRange(m.range, " ")
            }
        // Dates and times ("17:12 26-10-25", "2026/10/25") out of the way too
        work = work.replace(RX5, " ")
            .replace(RX6, " ")

        // Balance
        var balance: Double? = null
        var balanceRange: IntRange? = null
        RX7.find(work)?.let { m ->
            balance = toNumber(m.groupValues[1]); balanceRange = m.groups[1]!!.range
        }

        // Fee
        var fee = 0.0
        var feeRange: IntRange? = null
        RX8.find(work)?.let { m ->
            fee = toNumber(m.groupValues[1]) ?: 0.0; feeRange = m.groups[1]!!.range
        }

        fun taken(r: IntRange) = (balanceRange?.let { it.first == r.first } ?: false) || (feeRange?.let { it.first == r.first } ?: false)

        // Amount
        var amount: Double? = null
        val amountPatterns = AMOUNT_RX
        loop@ for (p in amountPatterns) {
            for (m in p.findAll(work)) {
                val g = m.groups[1]!!
                if (taken(g.range)) continue
                // a phone number is never an amount
                if (g.value.replace(",", "").startsWith("01") && g.value.length >= 10) continue
                amount = toNumber(g.value)
                if (amount != null) break@loop
            }
        }

        // My own wallet number (shown in received messages) – not the other party
        var own: String? = null
        RX9.find(work)?.let { own = it.groupValues[1] }

        // Other party's phone
        val phone = RX10.findAll(work)
            .map { it.groupValues[1] }.firstOrNull { it != own }

        // Name written in the message
        var name: String? = null
        RX11
            .find(work)?.let { name = cleanName(it.groupValues[1]) }

        // Type
        var type = TxType.UNKNOWN
        var kind = ""
        if (IGNORE_WORDS.any { low.contains(fold(it)) }) {
            type = TxType.IGNORE
        } else {
            var best = Int.MAX_VALUE
            for ((w, t, k) in TYPE_WORDS) {
                val i = low.indexOf(fold(w.lowercase()))
                if (i >= 0 && i < best) { best = i; type = t; kind = k }
            }
            if (type == TxType.OUT && kind == "تحويل" && TO_ME.any { low.contains(fold(it)) }) {
                type = TxType.IN; kind = "استلام"
            }
        }
        if (amount == null && type != TxType.IGNORE) {
            // no money moved (offers, info) -> not a transaction
            type = TxType.IGNORE
        }

        return Parsed(sms, wallet, type, amount, fee, balance, phone, name, txId, signature(wallet, text), kind, own)
    }

    /** First few words of the message without numbers – used to learn a message shape once the user classifies it. */
    fun signature(wallet: Wallet, normalizedText: String): String {
        val words = normalizedText
            .replace(RX12, " ")
            .replace(RX13, " ")
            .split(RX3).filter { it.isNotBlank() }
            .take(4)
        return wallet.key + "|" + words.joinToString(" ").lowercase()
    }
}
