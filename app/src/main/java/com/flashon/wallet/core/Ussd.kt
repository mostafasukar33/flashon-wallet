package com.flashon.wallet.core

/**
 * A Vodafone Cash transfer by its USSD code (*9*7*number*amount#), the way a person does it on the
 * phone: dial the code, type the PIN when asked, confirm when the screen repeats the same number,
 * and read the answer. This part only reads each screen's text and says what to do next; the
 * phone part (UssdService) finds the screen, types and taps. Pure Kotlin, tested in coretest.
 *
 * It never types the PIN into a screen it doesn't understand, never types it twice (a wrong PIN
 * three times locks the wallet), and only confirms a screen that names the same number.
 */
object Ussd {
    /** Vodafone Cash "transfer money": {n} = the number, {a} = the amount. */
    const val DEFAULT_VF = "*9*7*{n}*{a}#"
    const val MAX_AMOUNT = 60_000L

    /** The code to dial, or null if the template is broken (it must hold {n} and {a}, and only dial keys). */
    fun code(template: String?, to: String, amount: Long): String? {
        val t = (template ?: "").trim().ifBlank { DEFAULT_VF }
        if (!t.contains("{n}") || !t.contains("{a}")) return null
        val c = t.replace("{n}", to).replace("{a}", amount.toString())
        return if (Regex("^[0-9*#+]{4,60}$").matches(c)) c else null
    }

    /** An Egyptian mobile number written any way (+20, spaces, Arabic digits) as 01xxxxxxxxx, or null. */
    fun mobile(s: String?): String? {
        var d = Parser.normalize(s ?: "").filter { it in '0'..'9' }
        if (d.startsWith("0020")) d = "0" + d.substring(4)
        else if (d.startsWith("20") && d.length == 12) d = "0" + d.substring(2)
        else if (d.length == 10 && d.startsWith("1")) d = "0$d"
        return if (Regex("^01[0125][0-9]{8}$").matches(d)) d else null
    }

    /** A whole amount of pounds the code can carry (the phone keypad has no decimal point). */
    fun amount(s: String?): Long? {
        val t = Parser.normalize(s ?: "").replace(",", "").trim()
        val v = t.toDoubleOrNull() ?: return null
        if (v < 1 || v > MAX_AMOUNT || v != Math.floor(v)) return null
        return v.toLong()
    }

    fun pinOk(p: String?) = p != null && Regex("^[0-9]{4,8}$").matches(p)

    /**
     * Is this Vodafone Cash SMS the proof of a transfer of [amount] to [to] started at [startedAt]: money out
     * (or not understood yet), the same amount, the same number, from a minute before to 20 minutes after.
     */
    fun proves(p: Parsed, to: String, amount: Double, startedAt: Long): Boolean {
        if (p.wallet != Wallet.VODAFONE || p.type == TxType.IN || p.type == TxType.IGNORE) return false
        val a = p.amount ?: return false
        if (to.length < 10 || Math.abs(a - amount) > 0.011) return false
        val digits = Parser.normalize(p.sms.body).replace(Regex("(?<=\\d)[\\s-]+(?=\\d)"), "")
        if (!(p.phone?.endsWith(to.takeLast(9)) == true || digits.contains(to.takeLast(9)))) return false
        val age = p.sms.time - startedAt
        return age >= -60_000L && age <= 20 * 60_000L
    }

    enum class Step {
        /** "Running USSD code…" or nothing yet: wait for the next screen. */
        WAIT,
        /** Asks for the PIN: type it and send. */
        PIN,
        /** Asks to confirm a transfer to the same number: send "1". */
        CONFIRM,
        /** A question it doesn't understand, or a second PIN question: cancel, never guess. */
        STOP,
        /** The final answer, and the transfer went through. */
        OK,
        /** The final answer, and it didn't. */
        FAIL,
        /** A final answer that says neither: shown to the user as it is (the SMS from Vodafone tells the rest). */
        UNSURE,
        /** A long message cut in pages ("… 0:مزيد"): send the key for the rest of it ([moreKey]), only reads more. */
        MORE
    }

    /** Written the way a person would; matched after the same folding as the screen text (أ→ا, ة→ه, ى→ي). */
    private fun rx(p: String) = Regex(Parser.fold(p), RegexOption.IGNORE_CASE)

    private val RUNNING = rx("running|جار[ٍيى]?\\s*(تشغيل|تنفيذ|الإرسال|الارسال)|ussd code|mmi code|please wait|برجاء الانتظار|يرجى الانتظار|من فضلك انتظر")
    private val PIN = rx("الرقم السري|الرقم السرى|رقم سري|رقم سرى|رقمك السري|الرقم السري الخاص|كلمة السر|كلمه السر|كلمة المرور|كلمه المرور|الباسورد|\\bpin\\b|password|passcode|secret code")
    private val CONFIRM = rx("للتأكيد|للتاكيد|لتأكيد|لتاكيد|تأكيد|تاكيد|confirm|هل تريد|هل انت متأكد|هل أنت متأكد|موافق\\s*؟|اضغط\\s*1|ادخل\\s*1|أدخل\\s*1|1\\s*[-.:)]\\s*(نعم|موافق|تأكيد|تاكيد|yes|ok|confirm)")
    private val WRONG = rx("غير صحيح|خطأ|خاطئ|غلط|incorrect|invalid|wrong|غير كاف|insufficient|فشل|لم تتم|لم يتم|failed|declined|مرفوض|تم رفض|تجاوز|exceeded|غير مسموح|not allowed|غير متاح|unavailable|موقوف|blocked|مغلق|تعذر|نأسف|عفوا|sorry|try again|حاول مره|حاول مرة|connection problem|غير مسجل|not registered|غير مشترك")
    // "تم تحويل" as words of its own: not inside "سيتم تحويل" (will be) or "لم يتم تحويل" (wasn't)
    private val STRONG_OK = rx("(?<!\\p{L})(?:تم التحويل|تم تحويل|تمت عملية التحويل)|بنجاح|successful")
    private val DONE = rx("تم التحويل|تم تحويل|تمت عملية|تمت العملية|تم ارسال|تم إرسال|بنجاح|successful|success|has been transferred|transferred|سيتم تحويل|جاري تنفيذ طلبك|جاري التنفيذ|تم استلام طلبك|طلبك قيد التنفيذ|سيصلك")

    /** Does this text ask something (the PIN, or to confirm): a question, even when it comes as a notification. */
    fun asks(text: String): Boolean { val t = clean(text); return PIN.containsMatchIn(t) || CONFIRM.containsMatchIn(t) }

    /** The screen's text with Arabic digits as ASCII and the number's digits kept together, for matching. */
    private fun clean(text: String) = Parser.fold(Parser.normalize(text))

    /** Does the screen name this number (the last 8 digits are enough: +20 / spaces / dashes vary). */
    fun namesNumber(text: String, to: String): Boolean {
        val digits = clean(text).replace(Regex("(?<=\\d)[\\s-]+(?=\\d)"), "")
        return digits.contains(to.takeLast(8))
    }

    /**
     * What to do with one USSD screen.
     * [hasInput] the screen has a box to type in (a question); without one it's the final answer.
     * [pinSent] / [confirmSent] what was already done in this transfer.
     */
    // "0:مزيد", "0- المزيد", "للمزيد اضغط 0", "المزيد: 00", "0 more"
    private val MORE_AFTER = Regex("(?:^|[\\s\\n])([0-9#*]{1,2})\\s*[:：\\-.)]?\\s*(?:مزيد|المزيد|للمزيد|التالي|more|next)", RegexOption.IGNORE_CASE)
    private val MORE_BEFORE = Regex("(?:مزيد|المزيد|للمزيد|التالي|more|next)\\s*(?:اضغط|ادخل|أدخل|اطلب|press)?\\s*[:：\\-]?\\s*([0-9#*]{1,2})(?![0-9])", RegexOption.IGNORE_CASE)

    /** The key that shows the rest of a long message, or null if this page says nothing about more. */
    fun moreKey(text: String): String? {
        val t = Parser.normalize(text)
        return MORE_AFTER.find(t)?.groupValues?.get(1) ?: MORE_BEFORE.find(t)?.groupValues?.get(1)
    }

    /**
     * What to do with one USSD screen.
     * [before] the pages already read of the same message (it was cut in pages): the number may be on the first.
     */
    fun step(text: String, hasInput: Boolean, to: String, pinSent: Boolean, confirmSent: Boolean, before: String = ""): Step {
        val t = clean(text)
        if (t.isBlank()) return Step.WAIT
        if (!hasInput) {
            val wrong = WRONG.containsMatchIn(t) && !STRONG_OK.containsMatchIn(t)
            if (RUNNING.containsMatchIn(t) && !DONE.containsMatchIn(t) && !wrong) return Step.WAIT
            if (wrong) return Step.FAIL
            return if (DONE.containsMatchIn(t)) Step.OK else Step.UNSURE
        }
        // Vodafone's "done" for this same number, even with a box under it ("… اطلب #9* و اشحن 0:مزيد"):
        // the transfer went through, and what comes after it is ads, so nothing more is sent
        if (STRONG_OK.containsMatchIn(t) && !WRONG.containsMatchIn(t) && namesNumber(clean(before) + "\n" + t, to)) return Step.OK
        // a question: the PIN once; the rest of a long message before any confirmation;
        // a confirmation once and only for the same number; nothing else
        if (PIN.containsMatchIn(t)) return if (pinSent) Step.STOP else Step.PIN
        if (moreKey(text) != null) return Step.MORE
        if (CONFIRM.containsMatchIn(t)) {
            if (confirmSent) return Step.STOP
            return if (namesNumber(clean(before) + "\n" + t, to)) Step.CONFIRM else Step.STOP
        }
        return Step.STOP
    }
}
