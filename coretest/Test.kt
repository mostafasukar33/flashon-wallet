import com.flashon.wallet.core.*
import java.time.ZoneId
import java.time.ZonedDateTime

fun t(s: String): Long = ZonedDateTime.parse(s).toInstant().toEpochMilli()
var fails = 0
fun check(name: String, ok: Boolean, info: Any? = null) {
    if (!ok) { fails++; println("FAIL $name -> $info") } else println("ok   $name")
}

fun main() {
    val out1 = RawSms("1", "VF-Cash", """تم تحويل 500 جنيه لرقم 011077762382 مصاريف الخدمة 2.5 جنيه رصيد حسابك فى فودافون كاش الحالي 8713.70.
تاريخ العملية 17:12 26-10-25 :
رقم العملية 0288885599899 :
 حول فلوس لأي محفظة وزود فرصك تكسب جنيه دهب! وكمان هيجيلك لحد 100ج كاش باك. http://vf.eg/vfcash?id=money_transfer""", t("2026-10-25T17:12:00+03:00"))
    val in1 = RawSms("2", "VF-Cash", """تم استلام مبلغ 7000 جنيه من رقم 01018064481 المسجل بإسم Mahmoud A Hemdan على رقم محفظتك  01005174644.
رصيدك الحالي: 64432.20 جنيه
تاريخ العملية: 20:53 26-10-18
رقم العملية: 024859239166
تابع كل مصروفاتك من تاريخ المعاملات على أبلكيشن أنا فودافون http://vf.eg/vfcash""", t("2026-10-18T20:53:00+03:00"))

    val a = Parser.parse(out1)!!
    check("out type", a.type == TxType.OUT, a)
    check("out amount", a.amount == 500.0, a.amount)
    check("out fee", a.fee == 2.5, a.fee)
    check("out balance", a.balance == 8713.70, a.balance)
    check("out phone", a.phone == "011077762382", a.phone)
    check("out txid", a.txId == "0288885599899", a.txId)
    check("out wallet", a.wallet == Wallet.VODAFONE)

    val b = Parser.parse(in1)!!
    check("in type", b.type == TxType.IN, b)
    check("in amount", b.amount == 7000.0, b.amount)
    check("in balance", b.balance == 64432.20, b.balance)
    check("in phone", b.phone == "01018064481", b.phone)
    check("in name", b.smsName == "Mahmoud A Hemdan", b.smsName)
    check("in fee", b.fee == 0.0, b.fee)

    // Arabic digits and different wording
    val c = Parser.parse(RawSms("3", "VF-Cash", "تم إضافة ١٬٢٥٠٫٥٠ ج.م إلى محفظتك من ٠١٢٣٤٥٦٧٨٩٠. رصيدك ١٠٠٠٠ ج.م", 0))!!
    check("arabic digits in", c.type == TxType.IN && c.amount == 1250.5 && c.balance == 10000.0 && c.phone == "01234567890", c)

    // Etisalat style guess
    val d = Parser.parse(RawSms("4", "Etisalat", "تم تحويل مبلغ 300 جنيه الى محفظتك من 01112223334. رصيد محفظتك الحالي هو 900 جنيه", 0))!!
    check("transfer to me = in", d.type == TxType.IN && d.wallet == Wallet.ETISALAT && d.amount == 300.0 && d.balance == 900.0, d)

    // failed transfer ignored
    val e = Parser.parse(RawSms("5", "VF-Cash", "عفواً، رصيدك غير كاف لإتمام عملية تحويل 500 جنيه. فودافون كاش", 0))!!
    check("failed ignored", e.type == TxType.IGNORE, e)

    // promo ignored
    val f = Parser.parse(RawSms("6", "VF-Cash", "حول فلوس لأي محفظة وزود فرصك تكسب جنيه دهب مع فودافون كاش", 0))!!
    check("promo ignored", f.type == TxType.IGNORE, f)

    // airtime (not wallet) not taken
    check("airtime skipped", Parser.parse(RawSms("7", "Vodafone", "تم خصم 10 جنيه من رصيدك الحالي 25 جنيه", 0)) == null)

    // withdraw
    val g = Parser.parse(RawSms("8", "VF-Cash", "تم سحب 2000 جنيه من محفظتك من ماكينة ATM. رصيدك الحالي 5000 جنيه. فودافون كاش", 0))!!
    check("withdraw", g.type == TxType.OUT && g.amount == 2000.0 && g.balance == 5000.0, g)

    // unknown shape -> inferred by balance
    val u1 = RawSms("9", "VF-Cash", "عملية ناجحة بقيمة 1000 جنيه فودافون كاش. الرصيد 9713.70", t("2026-10-26T10:00:00+03:00"))
    val led = Ledger(listOf(out1, in1, u1), Limits(), Learned(), ZoneId.of("Africa/Cairo"), t("2026-10-28T03:00:00+03:00"))
    val inf = led.all.first { it.sms.id == "9" }
    check("balance inference", inf.type == TxType.IN && inf.how == "balance", inf)

    // unknown stays unknown without balance clue, then learned rule applies
    val u2 = RawSms("10", "VF-Cash", "عملية بقيمة 50 جنيه على فودافون كاش", t("2026-10-27T10:00:00+03:00"))
    val led2 = Ledger(listOf(u2), Limits(), Learned())
    check("unknown box", led2.unknown.size == 1, led2.unknown)
    val sig = led2.unknown[0].signature
    val led3 = Ledger(listOf(u2), Limits(), Learned(rules = mapOf(sig to TxType.OUT)))
    check("learned rule", led3.txs.size == 1 && led3.txs[0].type == TxType.OUT, led3.txs)

    // Limits: the user's example. 20k at month start, +200k in, -150k out, and 40k of it today.
    val z = ZoneId.of("Africa/Cairo")
    val ex = mutableListOf<RawSms>()
    ex += RawSms("a", "VF-Cash", "تم استلام مبلغ 20000 جنيه من رقم 01000000001 رصيدك الحالي: 20000 جنيه فودافون كاش", t("2026-10-30T10:00:00+02:00"))
    ex += RawSms("b", "VF-Cash", "تم استلام مبلغ 200000 جنيه من رقم 01000000002 رصيدك الحالي: 220000 جنيه فودافون كاش", t("2026-11-02T10:00:00+02:00"))
    ex += RawSms("c", "VF-Cash", "تم تحويل 110000 جنيه لرقم 01000000003 رصيد حسابك فى فودافون كاش الحالي 110000", t("2026-11-05T10:00:00+02:00"))
    ex += RawSms("d", "VF-Cash", "تم تحويل 40000 جنيه لرقم 01000000003 رصيد حسابك فى فودافون كاش الحالي 70000", t("2026-11-10T09:00:00+02:00"))
    val lx = Ledger(ex, Limits(feesCount = true), Learned(), z, t("2026-11-10T12:00:00+02:00"))
    val n = lx.now(Account(Wallet.VODAFONE, ""))
    check("ex balance", n.balance == 70000.0, n)
    check("ex receive left", n.receiveLeft == 0.0, n)
    check("ex send left month", n.sendLeftMonth == 50000.0, n)
    check("ex send left day", n.sendLeftDay == 20000.0, n)
    check("ex can send now", n.canSendNow == 20000.0, n)
    check("ex locked", n.lockedNextMonth == 20000.0, n)
    val sm = lx.summary(java.time.YearMonth.of(2026, 11), Account(Wallet.VODAFONE, ""))
    check("ex start balance", sm.startBalance == 20000.0, sm)
    check("locked end of month", lx.lockedAtEndOf(java.time.YearMonth.of(2026, 11), Account(Wallet.VODAFONE, "")) == 20000.0)

    // Two Vodafone SIMs in one phone: each line has its own balance and limits
    val two = listOf(
        RawSms("s1", "VF-Cash", "تم استلام مبلغ 150000 جنيه من رقم 01000000001 على رقم محفظتك 01011111111. رصيدك الحالي: 150000 جنيه", t("2026-11-02T10:00:00+02:00"), "1"),
        RawSms("s2", "VF-Cash", "تم استلام مبلغ 100000 جنيه من رقم 01000000002 على رقم محفظتك 01022222222. رصيدك الحالي: 100000 جنيه", t("2026-11-03T10:00:00+02:00"), "2"),
        RawSms("s3", "VF-Cash", "تم تحويل 1000 جنيه لرقم 01000000003 رصيد حسابك فى فودافون كاش الحالي 149000", t("2026-11-04T10:00:00+02:00"), "1")
    )
    val lt = Ledger(two, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"))
    val l1 = lt.now(Account(Wallet.VODAFONE, "1")); val l2 = lt.now(Account(Wallet.VODAFONE, "2"))
    check("two lines accounts, no empty etisalat", lt.accounts.map { it.key } == listOf("vf1", "vf2"), lt.accounts)
    check("line1 balance", l1.balance == 149000.0 && l1.monthReceived == 150000.0 && l1.ownNumber == "01011111111", l1)
    check("line2 balance", l2.balance == 100000.0 && l2.monthReceived == 100000.0 && l2.monthSent == 0.0, l2)
    check("line label", lt.accountLabel(Account(Wallet.VODAFONE, "2")) == "فودافون كاش – خط 2", lt.accountLabel(Account(Wallet.VODAFONE, "2")))
    check("one line no label", lx.accountLabel(Account(Wallet.VODAFONE, "")) == "فودافون كاش")

    // User setup: line 1 Vodafone, line 2 Etisalat; Etisalat hidden in another setup
    val mix = listOf(
        RawSms("m1", "VF-Cash", "تم استلام مبلغ 1000 جنيه من رقم 01000000001. رصيدك الحالي: 1000 جنيه فودافون كاش", t("2026-11-02T10:00:00+02:00"), "1"),
        RawSms("m2", "Etisalat", "تم تحويل مبلغ 300 جنيه الى محفظتك من 01112223334. رصيد محفظتك الحالي هو 900 جنيه", t("2026-11-03T10:00:00+02:00"), "2"),
        RawSms("m3", "VF-Cash", "تم استلام مبلغ 50 جنيه من رقم 01000000009. رصيدك الحالي: 1050 جنيه فودافون كاش", t("2026-11-03T11:00:00+02:00"), "")
    )
    val lm = Ledger(mix, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"), lines = mapOf("1" to Wallet.VODAFONE, "2" to Wallet.ETISALAT))
    check("setup accounts", lm.accounts.map { it.key } == listOf("vf1", "et2"), lm.accounts)
    check("setup routing", lm.now(Account(Wallet.VODAFONE, "1")).balance == 1050.0 && lm.now(Account(Wallet.ETISALAT, "2")).balance == 900.0)
    val lh = Ledger(mix, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"), lines = mapOf("1" to Wallet.VODAFONE))
    check("hidden etisalat", lh.accounts.size == 1 && lh.all.none { it.wallet == Wallet.ETISALAT }, lh.accounts)
    // A new wallet with small limits
    val small = Limits(monthReceive = 5000.0, monthSend = 5000.0, daySend = 2000.0)
    val ls = Ledger(mix, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"), lines = mapOf("1" to Wallet.VODAFONE), accountLimits = mapOf("vf1" to small))
    val ns = ls.now(Account(Wallet.VODAFONE, "1"))
    check("own limits", ns.receiveLeft == 3950.0 && ns.canSendNow == 1050.0 && ns.sendLeftDay == 2000.0, ns)

    // Phone reports subscription ids instead of 1/2; user picks "line 1 = VF, line 2 = VF"
    val subs = listOf(
        RawSms("u1", "VF-Cash", "تم استلام مبلغ 100 جنيه من رقم 01000000001 على رقم محفظتك 01031819017. رصيدك الحالي: 100 جنيه", t("2026-11-02T10:00:00+02:00"), "s3"),
        RawSms("u2", "VF-Cash", "تم استلام مبلغ 200 جنيه من رقم 01000000002 على رقم محفظتك 01064063446. رصيدك الحالي: 200 جنيه", t("2026-11-02T11:00:00+02:00"), "s5"),
        RawSms("u3", "VF-Cash", "تم استلام مبلغ 5 جنيه من رقم 01000000003 على رقم محفظتك 01064063446. رصيدك الحالي: 205 جنيه", t("2026-11-02T12:00:00+02:00"), "s5")
    )
    val lsub = Ledger(subs, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"), lines = mapOf("1" to Wallet.VODAFONE, "2" to Wallet.VODAFONE))
    check("sub ids kept apart", lsub.accounts.map { it.key } == listOf("vfs3", "vfs5") &&
        lsub.now(lsub.accounts[0]).balance == 100.0 && lsub.now(lsub.accounts[1]).balance == 205.0, lsub.accounts)
    check("sub label", lsub.accountLabel(lsub.accounts[1]) == "فودافون كاش – خط 2", lsub.accountLabel(lsub.accounts[1]))
    val lsub2 = Ledger(subs, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"), lines = mapOf("s3" to Wallet.VODAFONE, "s5" to Wallet.VODAFONE))
    check("sub keys direct", lsub2.now(lsub2.accounts[1]).balance == 205.0)
    val lauto = Ledger(subs, Limits(), Learned(), z, t("2026-11-04T12:00:00+02:00"))
    check("auto sub split", lauto.accounts.size == 2 && lauto.accountLabel(lauto.accounts[0]) == "فودافون كاش – خط 1", lauto.accounts)

    // one-pass summaries match the per-month ones
    val allS = lx.allSummaries()
    val one = allS[java.time.YearMonth.of(2026, 11)]!!["vf"]!!
    val ref = lx.summary(java.time.YearMonth.of(2026, 11), Account(Wallet.VODAFONE, ""))
    check("one-pass summary", one.inTotal == ref.inTotal && one.outTotal == ref.outTotal && one.startBalance == ref.startBalance && one.endBalance == ref.endBalance, one to ref)

    // speed: 3 years of a busy wallet on two lines
    val big = ArrayList<RawSms>()
    var bal = 0.0
    val t0 = t("2024-01-01T09:00:00+02:00")
    for (i in 0 until 6000) {
        val inMsg = i % 3 != 0
        val amt = 100.0 + (i % 50) * 37
        bal += if (inMsg) amt else -(amt + 2.5)
        val b = if (inMsg) "تم استلام مبلغ ${amt} جنيه من رقم 0100000${1000 + i % 300} على رقم محفظتك 0103181901${i % 2}. رصيدك الحالي: ${"%.2f".format(java.util.Locale.US, bal)} جنيه رقم العملية: ${100000000 + i}"
                else "تم تحويل ${amt} جنيه لرقم 0111000${1000 + i % 300} مصاريف الخدمة 2.5 جنيه رصيد حسابك فى فودافون كاش الحالي ${"%.2f".format(java.util.Locale.US, bal)}. رقم العملية ${200000000 + i}"
        big += RawSms("b$i", "VF-Cash", b, t0 + i * 4L * 3600_000, if (i % 2 == 0) "s3" else "s5")
    }
    var ms = System.nanoTime()
    var lb = Ledger(big, Limits(), Learned(), z, t0 + 6000L * 4 * 3600_000)
    lb.allSummaries(); lb.accounts.forEach { lb.now(it) }
    val first = (System.nanoTime() - ms) / 1_000_000
    ms = System.nanoTime()
    lb = Ledger(big, Limits(), Learned(), z, t0 + 6000L * 4 * 3600_000)
    lb.allSummaries(); lb.accounts.forEach { lb.now(it) }
    val second = (System.nanoTime() - ms) / 1_000_000
    println("speed 6000 msgs: first ${first}ms, again ${second}ms, txs=${lb.txs.size}, months=${lb.months().size}")
    check("speed again", second < 400, second)

    // Real Etisalat messages
    val etOut = """تم تحويل مبلغ 3000.00 ج.م الى رقم 01012936963 بنجاح. 
رسوم التحويل 1.00  جنيه.
رصيد محفظتك الحالى 2153.29. 
استخدم etisalat money هتكسب و العب هتكسب هدايا تانية كتير http://spr.ly/Spin_The_Wheel
 وكمان تقدر تستمتع بخصم  %30على وجبة كومبو من Buffalo Burger ب 160 جنيه بدل225 لما تدفع ب etisalat money http://spr.ly/BuffaloBurger2"""
    val etIn = """تم إستلام مبلغ 4500.00 ج.م من رقم 01063470062 المسجل باسمMohamed G Derar   بنجاح. رصيد محفظتك الحالى 60157.29 ج.م.
استخدم etisalat money هتكسب و العب هتكسب هدايا تانية كتير http://spr.ly/Spin_The_Wheel
 وكمان تقدر تستمتع بخصم  %30على وجبة كومبو من Buffalo Burger ب 160 جنيه بدل225 لما تدفع ب http://spr.ly/BuffaloBurger2 etisalat money"""
    for (snd in listOf("etisalat", "e& money", "4321", "eand money")) {
        val o = Parser.parse(RawSms("eo", snd, etOut, 0))
        check("et out [$snd]", o != null && o.wallet == Wallet.ETISALAT && o.type == TxType.OUT && o.amount == 3000.0 && o.fee == 1.0 && o.balance == 2153.29 && o.phone == "01012936963", o)
        val i = Parser.parse(RawSms("ei", snd, etIn, 0))
        check("et in [$snd]", i != null && i.wallet == Wallet.ETISALAT && i.type == TxType.IN && i.amount == 4500.0 && i.balance == 60157.29 && i.phone == "01063470062" && i.smsName == "Mohamed G Derar", i)
    }

    // ---- the balance is the wallet's, not the phone credit / bundle price ----
    val rc = Parser.parse(RawSms("rc", "VF-Cash", "تم شحن رصيد بقيمة 52 جنيه لرقم 01012345678 بنجاح. رصيد حسابك فى فودافون كاش الحالي 9948.00", 0))
    check("recharge: wallet balance, not the credit", rc != null && rc.balance == 9948.0 && rc.amount == 52.0, rc)
    val rc2 = Parser.parse(RawSms("rc2", "VF-Cash", "تم شحن رصيد لرقمك. رصيد حسابك الحالي 9948 جنيه", 0))
    check("recharge: one balance word doesn't hide the next", rc2?.balance == 9948.0, rc2)
    val bdl = Parser.parse(RawSms("bd", "VF-Cash", "تم شراء باقة فليكس بمبلغ 52 جنيه من محفظتك. رصيد محفظتك الحالي 9,948 جنيه", 0))
    check("bundle from the wallet", bdl != null && bdl.balance == 9948.0 && bdl.amount == 52.0 && bdl.type == TxType.OUT, bdl)
    check("operator recharge message isn't the wallet", Parser.parse(RawSms("op", "Vodafone", "تم شحن رصيد 52 جنيه بنجاح عن طريق فودافون كاش. رصيدك الحالي 52.00 جنيه", 0)) == null)
    check("operator bundle renewal isn't the wallet", Parser.parse(RawSms("op2", "Vodafone", "تم تجديد باقة فليكس 52 بنجاح وتم خصم 52 جنيه. ادفع فواتيرك بفودافون كاش", 0)) == null)
    check("a wallet message from the operator's name still counts",
        Parser.parse(RawSms("op3", "Vodafone", "تم شراء باقة بمبلغ 52 جنيه. رصيد حسابك فى فودافون كاش الحالي 9948.00", 0))?.balance == 9948.0)
    check("received still reads", Parser.parse(in1)?.balance == 64432.20)
    val zb = ZoneId.of("Africa/Cairo")
    val b1 = RawSms("b1", "VF-Cash", "تم استلام مبلغ 10000 جنيه من رقم 01011112222 على رقم محفظتك 01005174644. رصيدك الحالي: 10000.00 جنيه", t("2026-11-01T10:00:00+02:00"))
    val b2 = RawSms("b2", "VF-Cash", "تم دفع 52 جنيه لباقة فليكس رصيدك 52", t("2026-11-02T10:00:00+02:00"))
    val lbb = Ledger(listOf(b1, b2), Limits(), Learned(), zb, t("2026-11-03T10:00:00+02:00"))
    check("balance = price is dropped, the wallet keeps 10,000", lbb.now(lbb.accounts.first()).balance == 10000.0, lbb.now(lbb.accounts.first()).balance)
    val b3 = RawSms("b3", "VF-Cash", "عرض فودافون كاش: اشحن واكسب، رصيدك الحالي 7 نقاط بقيمة", t("2026-11-02T11:00:00+02:00"))
    val b4 = RawSms("b4", "VF-Cash", "حول فلوس لأي محفظة فودافون كاش ورصيدك 3 جنيه هدية", t("2026-11-02T12:00:00+02:00"))
    val lo = Ledger(listOf(b1, b3, b4), Limits(), Learned(), zb, t("2026-11-03T10:00:00+02:00"))
    check("an offer doesn't change the balance", lo.now(lo.accounts.first()).balance == 10000.0, lo.now(lo.accounts.first()).balance)
    val b5 = RawSms("b5", "VF-Cash", "تم تحويل 500 جنيه لرقم 01099998888 مصاريف الخدمة 1 جنيه رصيد حسابك فى فودافون كاش الحالي 9499.00", t("2026-11-02T13:00:00+02:00"))
    val lc = Ledger(listOf(b1, b5), Limits(), Learned(), zb, t("2026-11-03T10:00:00+02:00"))
    check("a transfer that adds up keeps its balance", lc.now(lc.accounts.first()).balance == 9499.0, lc.now(lc.accounts.first()).balance)
    val b6 = RawSms("b6", "VF-Cash", "تم تحويل 100 جنيه لرقم 01099998888 رصيد حسابك فى فودافون كاش الحالي 300.00", t("2026-11-02T14:00:00+02:00"))
    val lj = Ledger(listOf(b1, b6), Limits(), Learned(), zb, t("2026-11-03T10:00:00+02:00"))
    check("a transfer after a missing message is trusted", lj.now(lj.accounts.first()).balance == 300.0, lj.now(lj.accounts.first()).balance)

    // ---- the transfer by USSD code (Ussd.kt) ----
    val to = "01012345678"
    check("code default", Ussd.code(null, to, 500) == "*9*7*01012345678*500#", Ussd.code(null, to, 500))
    check("code custom", Ussd.code("*9*7*{n}*{a}#", to, 75) == "*9*7*01012345678*75#")
    check("code broken template", Ussd.code("*9*{a}#", to, 75) == null && Ussd.code("*9*7*{n}*{a}#;rm", to, 1) == null)
    check("mobile +20", Ussd.mobile("+20 101 234 5678") == to, Ussd.mobile("+20 101 234 5678"))
    check("mobile arabic", Ussd.mobile("٠١٠١٢٣٤٥٦٧٨") == to)
    check("mobile bad", Ussd.mobile("0101234567") == null && Ussd.mobile("01312345678") == null && Ussd.mobile("") == null)
    check("amount", Ussd.amount("1,500") == 1500L && Ussd.amount("١٢٠") == 120L)
    check("amount bad", Ussd.amount("12.5") == null && Ussd.amount("0") == null && Ussd.amount("999999") == null && Ussd.amount("x") == null)
    check("pin", Ussd.pinOk("123456") && !Ussd.pinOk("12a456") && !Ussd.pinOk("12") && !Ussd.pinOk(null))
    fun st(text: String, input: Boolean, pin: Boolean = false, conf: Boolean = false) = Ussd.step(text, input, to, pin, conf)
    check("ussd running", st("جارٍ تشغيل رمز USSD…", false) == Ussd.Step.WAIT)
    check("ussd running en", st("USSD code running…", false) == Ussd.Step.WAIT)
    check("ussd pin", st("من فضلك ادخل الرقم السري الخاص بمحفظتك لتحويل 500 جنيه الى 01012345678", true) == Ussd.Step.PIN)
    check("ussd pin en", st("Please enter your PIN", true) == Ussd.Step.PIN)
    check("ussd pin twice = stop", st("الرقم السري غير صحيح، ادخل الرقم السري مرة اخرى", true, pin = true) == Ussd.Step.STOP)
    check("ussd confirm same number", st("سيتم تحويل 500 جنيه إلى ٠١٠١٢٣٤٥٦٧٨ بمصاريف 1 جنيه. للتأكيد اضغط 1", true, pin = true) == Ussd.Step.CONFIRM)
    check("ussd confirm spaced number", st("تحويل 500 ج الى 010 1234 5678\n1- تأكيد\n2- إلغاء", true, pin = true) == Ussd.Step.CONFIRM)
    check("ussd confirm other number = stop", st("سيتم تحويل 500 جنيه إلى 01099999999 للتأكيد اضغط 1", true, pin = true) == Ussd.Step.STOP)
    check("ussd confirm twice = stop", st("للتأكيد اضغط 1 لتحويل الى 01012345678", true, pin = true, conf = true) == Ussd.Step.STOP)
    check("ussd menu = stop", st("1- تحويل فلوس\n2- شحن رصيد\n3- دفع فواتير", true) == Ussd.Step.STOP)
    check("ussd done", st("تم تحويل 500 جنيه لرقم 01012345678 بنجاح. رصيدك الحالي 8213.70", false, pin = true) == Ussd.Step.OK)
    check("ussd wrong pin final", st("عفواً، الرقم السري غير صحيح", false, pin = true) == Ussd.Step.FAIL)
    check("ussd no money", st("رصيدك غير كافٍ لإتمام العملية", false, pin = true) == Ussd.Step.FAIL)
    check("ussd network", st("Connection problem or invalid MMI code.", false) == Ussd.Step.FAIL)
    check("ussd limit", st("عفواً لقد تجاوزت الحد اليومي للتحويل", false, pin = true) == Ussd.Step.FAIL)
    check("ussd unsure", st("طلبك وصل وهيتراجع", false, pin = true) == Ussd.Step.UNSURE)

    // ---- Vodafone's real screen, cut in pages ("0:مزيد") ----
    val me = "01005174644"
    val page1 = "لتحويل 5 ج بالإضافة لـ1 ج رسوم لرقم 01005174644 بإسم مصطفى م*** م**** 0:مزيد"
    check("real page 1: asks for the rest", Ussd.step(page1, true, me, false, false) == Ussd.Step.MORE, Ussd.step(page1, true, me, false, false))
    check("real page 1: the key is 0", Ussd.moreKey(page1) == "0", Ussd.moreKey(page1))
    check("more key: other wordings", Ussd.moreKey("للمزيد اضغط 00") == "00" && Ussd.moreKey("المزيد: 0") == "0" && Ussd.moreKey("0- المزيد") == "0" && Ussd.moreKey("9 more") == "9")
    check("more key: none", Ussd.moreKey("لمزيد من المعلومات اتصل بخدمة العملاء") == null && Ussd.moreKey("ادخل الرقم السري") == null)
    check("page 2: the PIN", Ussd.step("ادخل الرقم السري للتأكيد", true, me, false, false, page1) == Ussd.Step.PIN)
    check("page 2: confirm, the number was on page 1", Ussd.step("1:تأكيد\n2:إلغاء", true, me, true, false, page1) == Ussd.Step.CONFIRM)
    check("page 2: confirm for another number = stop", Ussd.step("1:تأكيد\n2:إلغاء", true, "01099999999", true, false, page1) == Ussd.Step.STOP)
    check("page with the PIN and more: the PIN first", Ussd.step("ادخل الرقم السري 0:مزيد", true, me, false, false) == Ussd.Step.PIN)

    // ---- the rest of Vodafone's real transfer (second screenshot) ----
    val pinPage = "ع******** ادخل الرقم السري 1:للإعادة 2:لمعرفة الرسوم 00:رجوع"
    check("real PIN page", Ussd.step(pinPage, true, me, false, false, page1) == Ussd.Step.PIN, Ussd.step(pinPage, true, me, false, false, page1))
    val donePage = "تم تحويل 5 جنيه لرقم 01005174644 مصاريف الخدمة 1 جنيه . اطلب #9* و اشحن 0:مزيد"
    check("real done page with 0:مزيد = done, no more pages", Ussd.step(donePage, true, me, true, false) == Ussd.Step.OK, Ussd.step(donePage, true, me, true, false))
    check("done for another number isn't", Ussd.step(donePage, true, "01099999999", true, false) != Ussd.Step.OK)
    check("'will be transferred' isn't done", Ussd.step("سيتم تحويل 500 جنيه إلى 01012345678 للتأكيد اضغط 1", true, "01012345678", true, false) == Ussd.Step.CONFIRM)
    check("'wasn't transferred' isn't done", Ussd.step("عفوا لم يتم تحويل 500 جنيه لرقم 01012345678 0:مزيد", true, "01012345678", true, false) != Ussd.Step.OK)
    check("the ad after it = stop", Ussd.step("دلوقتي لاستخدام محفظتك دوس 1 00:رجوع", true, me, true, false) == Ussd.Step.STOP)

    // ---- the network hiccup from the third screenshot ----
    val mmi = "رسالة Vodafone حدثت مشكلة في الاتصال أو رمز MMI غير صالح."
    check("real MMI error = failed", Ussd.step(mmi, false, me, false, false) == Ussd.Step.FAIL, Ussd.step(mmi, false, me, false, false))
    check("real MMI error = a hiccup (try again)", Ussd.glitch(mmi) && Ussd.glitch("Connection problem or invalid MMI code."))
    check("a wrong PIN isn't a hiccup", !Ussd.glitch("عفواً، الرقم السري غير صحيح") && !Ussd.glitch("رصيدك غير كافٍ"))

    // ---- Vodafone's real answer to an answer put inside the code ("*9*7*N*A*0#" and "*9*7*N*A*PIN#") ----
    val badAnswer = "عفوا الرقم الذي ادخالته غير صحيح لتحويل 10000 ج بالإضافة ل15 ج رسوم لرقم 0:مزيد"
    check("real 'incorrect' answer = failed", Ussd.step(badAnswer, false, me, true, false) == Ussd.Step.FAIL, Ussd.step(badAnswer, false, me, true, false))
    check("real 'incorrect' answer isn't done or a PIN question", Ussd.step(badAnswer, true, me, true, false).let { it != Ussd.Step.OK && it != Ussd.Step.PIN && it != Ussd.Step.CONFIRM })

    // ---- the talk with no screen (phone locked with a password): replies only as "…#" ----
    check("quiet reply: the more key", Ussd.quietReply(Ussd.moreKey(page1)) == "0#", Ussd.quietReply(Ussd.moreKey(page1)))
    check("quiet reply: the PIN goes with # (never dialed as a call)", Ussd.quietReply("123456") == "123456#")
    check("quiet reply: confirm", Ussd.quietReply("1") == "1#")
    check("quiet reply: nothing but keys", Ussd.quietReply(null) == null && Ussd.quietReply("") == null && Ussd.quietReply("12 34") == null && Ussd.quietReply("1#") == null && Ussd.quietReply("0123456789012") == null)
    // Android gives a page that waits for an answer as "failed + Vodafone's words": read as a question (asks = true)
    check("quiet: page 1 = read the rest", Ussd.step(page1, true, me, false, false) == Ussd.Step.MORE)
    check("quiet: page 2 = the PIN", Ussd.step(pinPage, true, me, false, false, page1 + "\n") == Ussd.Step.PIN)
    check("quiet: done page = done", Ussd.step(donePage, true, me, true, false, page1 + "\n") == Ussd.Step.OK)
    check("quiet: MMI error as 'failed + words' = stop, a failure, a hiccup", Ussd.step(mmi, true, me, false, false) == Ussd.Step.STOP && Ussd.step(mmi, false, me, false, false) == Ussd.Step.FAIL && Ussd.glitch(mmi))
    check("quiet: wrong PIN talks about the PIN", Ussd.mentionsPin("عفواً، الرقم السري غير صحيح") && Ussd.mentionsPin(pinPage) && !Ussd.mentionsPin(donePage) && !Ussd.mentionsPin(page1))
    check("quiet: the same PIN question again after the PIN = stop", Ussd.step(pinPage, true, me, true, false, page1) == Ussd.Step.STOP)

    // ---- a question, even when it comes as a notification ----
    check("asks: PIN", Ussd.asks("لتأكيد تحويل 500 جنيه الى 01012345678 ادخل الرقم السري"))
    check("asks: confirm", Ussd.asks("سيتم تحويل 500 جنيه. للتأكيد اضغط 1"))
    check("asks: not a final answer", !Ussd.asks("تم تحويل 500 جنيه لرقم 01012345678 بنجاح"))

    // ---- Vodafone's SMS as the proof of a transfer made from the app ----
    val sent = t("2026-11-05T10:00:00+02:00")
    val proof = Parser.parse(RawSms("pf", "VF-Cash", "تم تحويل 750 جنيه لرقم 01012345678 مصاريف الخدمة 1 جنيه رصيد حسابك فى فودافون كاش الحالي 8000.00 رقم العملية: 0288885599000", sent + 40_000))!!
    check("proof: same amount and number", Ussd.proves(proof, "01012345678", 750.0, sent))
    check("proof: other amount", !Ussd.proves(proof, "01012345678", 700.0, sent))
    check("proof: other number", !Ussd.proves(proof, "01099999999", 750.0, sent))
    check("proof: an hour later", !Ussd.proves(proof, "01012345678", 750.0, sent - 3_600_000L))
    check("proof: money in isn't", !Ussd.proves(Parser.parse(in1)!!, "01018064481", 7000.0, in1.time))
    check("proof: transaction number kept", proof.txId == "0288885599000", proof.txId)

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
