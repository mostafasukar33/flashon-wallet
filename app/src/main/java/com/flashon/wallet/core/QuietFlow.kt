package com.flashon.wallet.core

/**
 * The transfer with no screen at all (the Android locked with a password), one Vodafone page at a time. Pure
 * Kotlin, tested in coretest; the phone part (Xfer + Quiet) only sends what this says and hands back each answer.
 *
 * What the Infinix showed (trace of 20 Nov): Vodafone's talk stays open between the app's background sends, but
 * Android sends a reply as USSD only when it's one or two digits or ends in "#" (anything else it dials as a call),
 * and Vodafone's menus take "0#" as "0#", not "0" ("عفوا الرقم الذي ادخالته غير صحيح …", then "Max Number of Menu
 * Retries is Reached"). So:
 *  - menu keys go bare ("0", "1", "00": Android sends those as USSD as they are);
 *  - typed values (the number, the amount, the PIN) can only go with "#" after them. Before the PIN ever goes, the
 *    recipient's number goes with "#" in Vodafone's own "enter the number" box: if Vodafone moves on to the amount,
 *    its boxes take "#" ([hashOk]) and the PIN goes the same way; if not ([hashBad]), the PIN is never sent.
 * That's why it starts from Vodafone's transfer menu ("*9*7#", else the wallet menu "*9#") and not from the code
 * with the number and amount in it (that one asks the PIN right after, with no box to try "#" in first).
 */
class QuietFlow(val to: String, val amount: Long, hashKnown: Boolean) {
    sealed class Act {
        /** Send this string (a new code, or an answer in the open talk). */
        data class Send(val s: String, val what: String) : Act()
        /** Send the PIN, with "#" after it ([pinReply]). */
        object Pin : Act()
        /** The transfer is over: "ok", "fail", "stop" or "unsure", with Vodafone's words. */
        data class End(val st: String, val text: String) : Act()
        /** Nothing that moves money was sent: stop talking with no screen (the screen way goes on). */
        data class Off(val why: String) : Act()
    }

    private enum class Stage { MENU, NUMBER, AMOUNT, AFTER }

    private var stage = Stage.MENU
    private var triedWalletMenu = false
    private var menuSteps = 0
    var hashOk = hashKnown
        private set
    /** Vodafone didn't take a typed value with "#" after it: no PIN this way on this phone. */
    var hashBad = false
        private set
    /** Vodafone talked about the PIN after it went with "#" (wrong, or asked again). */
    var pinRejected = false
        private set
    var pinSent = false
        private set
    private var confirmSent = false
    private var more = 0
    private val pages = StringBuilder()

    fun first(): String = FIRST

    fun pinReply(pin: String): String? = Ussd.quietReply(pin)

    /** What to do with Vodafone's answer [text]; [androidAsks] = Android said it waits for an answer. */
    fun next(text: String, androidAsks: Boolean): Act {
        val t = text.trim()
        if (t.isEmpty()) return if (pinSent) Act.End("unsure", "فودافون مردتش بكلام بعد الرقم السري. استنى رسالة فودافون") else Act.Off("فودافون مردتش بكلام")
        val question = Ussd.quietAsks(t, androidAsks) || asksNumber(t) || asksAmount(t)
        return when (stage) {
            Stage.MENU -> menu(t, question)
            // the answer to the number with "#": Vodafone moved on to the amount = its boxes take "#"
            Stage.NUMBER -> when {
                asksAmount(t) && !wrong(t) -> {
                    hashOk = true
                    stage = Stage.AMOUNT
                    Act.Send(Ussd.quietReply(amount.toString())!!, "كتب المبلغ بـ «#»")
                }
                Ussd.glitch(t) -> Act.Off("مشكلة في الشبكة بعد الرقم")
                // said no, or asked for the number again: its boxes don't take "#", so the PIN never goes this way
                wrong(t) || asksNumber(t) -> { hashBad = true; Act.Off("فودافون مقبلتش الرقم مكتوب بـ «#» ($t)") }
                else -> Act.Off("صفحة فودافون بعد الرقم مش زي المتوقع ($t)")
            }
            Stage.AMOUNT, Stage.AFTER -> page(t, question)
        }
    }

    /** Marks the PIN as sent ([Act.Pin] was done). */
    fun pinDone() { pinSent = true; stage = Stage.AFTER }

    private fun menu(t: String, question: Boolean): Act {
        if (asksNumber(t) && question) {
            stage = Stage.NUMBER
            return Act.Send(Ussd.quietReply(to)!!, "كتب الرقم بـ «#» (لو فودافون قبلته يبقى الرقم السري هيتقبل كده)")
        }
        if (!question || wrong(t)) {
            // "*9*7#" isn't a menu here: the wallet's own menu, once (the last talk ended with this answer)
            if (!triedWalletMenu) { triedWalletMenu = true; menuSteps = 0; return Act.Send(WALLET_MENU, "طلب قايمة المحفظة $WALLET_MENU") }
            return Act.Off("قايمة فودافون مش زي المتوقع ($t)")
        }
        if (++menuSteps > 4) return Act.Off("ملقاش «تحويل» في قايمة فودافون")
        transferKey(t)?.let { return Act.Send(it, "اختار «تحويل» ($it)") }
        Ussd.moreKey(t)?.let { k -> bare(k)?.let { return Act.Send(it, "طلب باقي القايمة ($it)") } }
        return Act.Off("ملقاش «تحويل» في قايمة فودافون ($t)")
    }

    private fun page(t: String, question: Boolean): Act {
        val step = Ussd.step(t, question, to, pinSent, confirmSent, pages.toString())
        return when (step) {
            Ussd.Step.MORE -> {
                val k = Ussd.moreKey(t)?.let { bare(it) }
                if (k == null || more >= 4) off(t, "فودافون سألت سؤال مش مفهوم")
                else { more++; pages.append(t).append('\n'); Act.Send(k, "طلب باقي الرسالة ($k)") }
            }
            Ussd.Step.PIN -> if (hashOk && !pinSent) Act.Pin else off(t, "فودافون سألت عن الرقم السري قبل ما يتأكد إن خاناتها بتقبل «#»")
            Ussd.Step.CONFIRM -> { confirmSent = true; Act.Send("1", "أكد بـ 1") }
            Ussd.Step.OK -> Act.End("ok", t)
            Ussd.Step.UNSURE, Ussd.Step.WAIT -> if (pinSent) Act.End("unsure", t) else off(t, "رد فودافون مش واضح")
            Ussd.Step.FAIL, Ussd.Step.STOP -> {
                val failed = step == Ussd.Step.FAIL || wrong(t)
                when {
                    pinSent && Ussd.mentionsPin(t) -> { pinRejected = true; Act.End("fail", t) }
                    !pinSent && (Ussd.glitch(t) || !failed) -> off(t, if (failed) "مشكلة في الشبكة" else "فودافون سألت سؤال مش مفهوم")
                    else -> Act.End(if (failed) "fail" else "stop", pages.toString() + t)
                }
            }
        }
    }

    private fun off(t: String, why: String): Act = if (pinSent) Act.End("unsure", t) else Act.Off(why)

    companion object {
        const val FIRST = "*9*7#"
        const val WALLET_MENU = "*9#"

        private fun rx(p: String) = Regex(Parser.fold(p), RegexOption.IGNORE_CASE)
        private val PIN_WORDS = rx("السري|السرى|\\bpin\\b|password")
        private val NUMBER = rx("(ادخل|أدخل|اكتب|برجاء ادخال|برجاء إدخال|من فضلك ادخل)\\s*(ال)?رقم|رقم\\s*(ال)?(موبايل|محمول|هاتف|تليفون|مستلم|مستفيد|محفظه|محفظة)|enter\\s*(the\\s*)?(mobile|number|msisdn|recipient)")
        private val AMOUNT = rx("(ادخل|أدخل|اكتب|برجاء ادخال|برجاء إدخال|من فضلك ادخل)\\s*(ال)?(مبلغ|قيمه|قيمة)|enter\\s*(the\\s*)?amount")
        private val WRONG = rx("غير صحيح|خطأ|خاطئ|عفوا|نأسف|invalid|incorrect|wrong|retries|غير صالح|مرفوض|فشل|لم يتم")
        private val NOT_THIS = rx("دولي|بنك|بنكي|رصيد|كارت|فيزا|ميزه|ميزة|انستا|instapay|international|bank|card|كاش اوت|سحب")
        private val OPTION = Regex("(?:^|[\\s\\n])([0-9]{1,2})\\s*[-:.)]\\s*([^\\n0-9]{2,40})")

        private fun clean(t: String) = Parser.fold(Parser.normalize(t))
        fun asksNumber(t: String): Boolean = clean(t).let { NUMBER.containsMatchIn(it) && !PIN_WORDS.containsMatchIn(it) }
        fun asksAmount(t: String): Boolean = clean(t).let { AMOUNT.containsMatchIn(it) && !PIN_WORDS.containsMatchIn(it) }
        private fun wrong(t: String) = WRONG.containsMatchIn(clean(t))

        /** A menu key Android sends as USSD as it is (one digit, or "00"); anything else would be dialed as a call. */
        fun bare(k: String): String? = if (Regex("^[0-9]$").matches(k) || k == "00") k else null

        /** The menu key for sending money to a wallet ("1-تحويل أموال"), not international / bank / credit. */
        fun transferKey(t: String): String? {
            for (m in OPTION.findAll(Parser.normalize(t))) {
                val label = Parser.fold(m.groupValues[2])
                if (label.contains(Parser.fold("تحويل")) && !NOT_THIS.containsMatchIn(label)) bare(m.groupValues[1])?.let { return it }
            }
            return null
        }
    }
}
