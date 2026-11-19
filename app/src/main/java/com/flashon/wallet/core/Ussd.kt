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
    private val WRONG = rx("غير صحيح|خطأ|خاطئ|غلط|incorrect|invalid|wrong|غير كاف|insufficient|فشل|لم تتم|لم يتم|failed|declined|مرفوض|تم رفض|تجاوز|exceeded|غير مسموح|not allowed|غير متاح|unavailable|موقوف|blocked|مغلق|تعذر|نأسف|عفوا|sorry|try again|حاول مره|حاول مرة|connection problem|غير مسجل|not registered|غير مشترك|غير صالح|غير صالحه|مشكلة|مشكله|problem")
    // "تم تحويل" as words of its own: not inside "سيتم تحويل" (will be) or "لم يتم تحويل" (wasn't)
    private val STRONG_OK = rx("(?<!\\p{L})(?:تم التحويل|تم تحويل|تمت عملية التحويل)|بنجاح|successful")
    private val DONE = rx("تم التحويل|تم تحويل|تمت عملية|تمت العملية|تم ارسال|تم إرسال|بنجاح|successful|success|has been transferred|transferred|سيتم تحويل|جاري تنفيذ طلبك|جاري التنفيذ|تم استلام طلبك|طلبك قيد التنفيذ|سيصلك")

    private val GLITCH = rx("مشكلة في الاتصال|مشكله في الاتصال|connection problem|invalid mmi|mmi غير صالح|رمز mmi|خطأ في الشبكة|network error|ussd error")

    /** The phone or the network failed to carry the code ("حدثت مشكلة في الاتصال أو رمز MMI غير صالح"): Vodafone never saw it. */
    fun glitch(text: String): Boolean = GLITCH.containsMatchIn(clean(text))

    // ---------- the transfer with no screen (one code, the answers joined with "*") ----------

    /** Where the code without the PIN got to. */
    sealed class Probe {
        /** The next thing Vodafone expects is the PIN: send the same code with "*PIN" added. [before] = what it showed. */
        class Pin(val before: String) : Probe()
        /** Can't be done with no screen ([off] = not on this phone for now). Nothing was typed: do it the usual way. */
        class No(val why: String, val off: Boolean) : Probe()
    }

    // Vodafone's own question for this transfer, page 1: "لتحويل 5 ج بالإضافة ل1 ج رسوم لرقم 0100… بإسم … 0:مزيد"
    private val TRANSFER_Q = Regex("^\\s*لتحويل\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:ج|جنيه)")

    /**
     * Is this page 1 of Vodafone's PIN question for this same transfer, cut in pages before it gets to the PIN.
     * On the phone the rest of it is "ع******** ادخل الرقم السري 1:للإعادة 2:لمعرفة الرسوم 00:رجوع", and in one
     * code the pages take no key: the next answer in the code goes to that question (a "0" there came back as
     * "عفوا الرقم الذي ادخالته غير صحيح لتحويل 5 ج …"). Only for the same number and the same amount, and not an error.
     */
    fun pinPage1(text: String, to: String, amount: Long): Boolean {
        val t = clean(text)
        if (moreKey(text) == null || WRONG.containsMatchIn(t) || CONFIRM.containsMatchIn(t) || STRONG_OK.containsMatchIn(t)) return false
        val a = TRANSFER_Q.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return false
        return a == amount.toDouble() && namesNumber(t, to)
    }

    /**
     * Asks Vodafone [base]# ("*9*7*N*A#") with no PIN in it, and says if the next thing it expects is the PIN.
     * Nothing else is ever added to the code: in one code a page's "0:مزيد" isn't a key, the next answer goes
     * straight to Vodafone's question, so a "0" would be read as a (wrong) PIN. [ask] sends one code with no screen
     * and returns (true, Vodafone's text), (false, why) or null for no answer.
     */
    fun probe(base: String, to: String, amount: Long, ask: (String) -> Pair<Boolean, String>?, log: (String) -> Unit): Probe {
        val r = ask("$base#") ?: return Probe.No("مفيش رد من غير شاشة", false)
        if (!r.first) return Probe.No("الكود من غير شاشة مش متاح (${r.second})", true)
        log("فودافون (من غير شاشة): " + r.second)
        val t = clean(r.second)
        if (PIN.containsMatchIn(t) && !WRONG.containsMatchIn(t)) return Probe.Pin(r.second)
        if (pinPage1(r.second, to, amount)) {
            log("دي الصفحة الأولى من سؤال الرقم السري لنفس الرقم ونفس المبلغ")
            return Probe.Pin(r.second)
        }
        return Probe.No("فودافون قالت حاجة تانية غير سؤال الرقم السري", false)
    }

    /** Vodafone's answer to the code with the PIN: "ok", "fail", "stop" (asked to confirm), or "unsure". */
    fun verdict(res: String, to: String, before: String): String = when {
        step(res, true, to, true, false, before) == Step.OK -> "ok"
        asksPin(res) -> "fail"        // the PIN again: it was wrong
        asks(res) -> "stop"           // a confirmation after the PIN
        else -> when (step(res, false, to, true, false)) { Step.OK -> "ok"; Step.FAIL -> "fail"; else -> "unsure" }
    }

    /** Does this text ask for the PIN (the next thing to type is the PIN). */
    fun asksPin(text: String): Boolean = PIN.containsMatchIn(clean(text))

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
