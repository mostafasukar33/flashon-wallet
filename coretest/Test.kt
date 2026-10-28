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

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
