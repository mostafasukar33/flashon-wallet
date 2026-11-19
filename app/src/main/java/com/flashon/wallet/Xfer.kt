package com.flashon.wallet

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.flashon.wallet.core.Parser
import com.flashon.wallet.core.RawSms
import com.flashon.wallet.core.Ussd
import com.flashon.wallet.core.Wallet
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.SecureRandom

/**
 * Sends money from Vodafone Cash by its code (*9*7*number*amount#), from this phone's screen or asked
 * from the iPhone. The phone dials the code on the wallet's own SIM, and [UssdService] (Android's
 * accessibility, turned on once by the user) reads each screen Vodafone answers with and does what a
 * person would: types the PIN when asked, confirms only a screen that names the same number, and reads
 * the answer, which goes back to the iPhone. The rules for each screen are in [Ussd] (tested on the PC).
 *
 * The PIN is never saved: it's typed into Vodafone's PIN screen once and wiped from memory. One transfer
 * at a time; a transfer that gets no answer in 75 seconds is called off.
 */
object Xfer {
    private const val CH = "xfer"
    private const val NOTE = 7100
    private const val TIMEOUT = 75_000L
    private const val KEEP = 20
    private val main = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()

    class Job(val id: String, val to: String, val amount: Long, pin: String, val code: String, val sub: Int, val from: String, val account: String) {
        private var pin: CharArray? = pin.toCharArray()
        val startedAt = System.currentTimeMillis()
        @Volatile var pinSent = false
        @Volatile var confirmSent = false
        @Volatile var dialed = false
        @Volatile var seen = false
        @Volatile var done = false
        @Volatile var last = ""
        @Volatile var lastNote = ""
        /** The pages already read of a message Vodafone cut in pages ("… 0:مزيد"), and how many. */
        val pages = StringBuilder()
        @Volatile var more = 0
        /** Questions already answered: the same one coming as a screen and as a notification is answered once. */
        private val answered = HashSet<String>()
        @Synchronized fun claim(text: String): Boolean = answered.add(text.replace(Regex("\\s+"), " ").trim())
        /** What Vodafone showed and what the app did, for the list (never the PIN). */
        val trace = java.util.Collections.synchronizedList(ArrayList<String>())
        fun log(s: String) { trace.add(s.replace(Regex("\\s+"), " ").trim().take(220)); if (trace.size > 12) trace.removeAt(0) }
        /** The PIN, once: it's gone from memory right after. */
        fun takePin(): String? { val p = pin ?: return null; pin = null; val s = String(p); p.fill('0'); return s }
        fun wipe() { pin?.fill('0'); pin = null }
    }

    @Volatile var job: Job? = null
        private set

    fun newId(): String = ByteArray(9).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // ---------- is everything allowed ----------

    fun callPerm(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    fun phonePerm(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun accComponent(ctx: Context) = ComponentName(ctx, UssdService::class.java)

    /** Is «التحويل التلقائي» turned on in Android's accessibility settings. */
    fun accOn(ctx: Context): Boolean = try {
        val on = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        on && list.split(':').any { ComponentName.unflattenFromString(it) == accComponent(ctx) }
    } catch (_: Exception) { false }

    fun remoteOn(store: Store) = store.getLong("xfer_remote") == 1L
    fun template(store: Store) = store.getStr("xfer_code")?.takeIf { it.isNotBlank() } ?: Ussd.DEFAULT_VF

    /**
     * For the screens. The iPhone gets it too, without what only this phone needs (see Sync):
     * ready = this phone can run a transfer by itself right now.
     */
    fun json(ctx: Context, store: Store, ledger: com.flashon.wallet.core.Ledger? = null): JSONObject {
        val led = ledger ?: store.ledger()
        val accs = JSONArray()
        // each Vodafone Cash wallet with its SIM line ("1", "2"), line 1 first: the transfer screen picks one
        for (a in vfAccounts(led)) {
            val n = led.now(a)
            accs.put(JSONObject().put("w", a.key).put("label", led.accountLabel(a)).put("own", n.ownNumber ?: JSONObject.NULL)
                .put("n", if (a.line.isBlank()) "" else led.lineNumber(a.line))
                .put("can", n.canSendNow).put("bal", n.balance ?: JSONObject.NULL))
        }
        val j = job
        val perm = callPerm(ctx) && phonePerm(ctx)
        val acc = accOn(ctx)
        return JSONObject().put("perm", perm).put("phone", phonePerm(ctx)).put("acc", acc).put("ready", perm && acc)
            .put("remote", remoteOn(store)).put("code", template(store)).put("def", Ussd.DEFAULT_VF)
            .put("busy", j != null && !j.done).put("line", Remote.state).put("accounts", accs).put("list", history(store))
    }

    private fun vfAccounts(led: com.flashon.wallet.core.Ledger) =
        led.accounts.filter { it.wallet == Wallet.VODAFONE }.sortedWith(compareBy(com.flashon.wallet.core.LINE_ORDER) { it.line })

    // ---------- the last transfers ----------

    private val HLOCK = Any()
    fun history(store: Store): JSONArray = try { JSONArray(store.getStr("xfers") ?: "[]") } catch (_: Exception) { JSONArray() }

    /** Adds or updates one transfer in the list (newest first). */
    fun record(store: Store, id: String, f: JSONObject) = synchronized(HLOCK) {
        val old = history(store)
        val out = JSONArray()
        var cur: JSONObject? = null
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (o.optString("id") == id) cur = o }
        val o = cur ?: JSONObject().put("id", id).put("at", System.currentTimeMillis())
        for (k in f.keys()) o.put(k, f.get(k))
        o.put("up", System.currentTimeMillis())
        out.put(o)
        for (i in 0 until old.length()) { val x = old.getJSONObject(i); if (x.optString("id") != id && out.length() < KEEP) out.put(x) }
        store.putStr("xfers", out.toString())
    }

    // ---------- the proof: Vodafone's SMS for it ----------

    /**
     * A new Vodafone Cash SMS that is a transfer this app made (same amount, same number, a few minutes after):
     * linked to it, so the list says "confirmed by Vodafone's SMS" and the receipt opens from it. The iPhone gets
     * the SMS text itself as the notification, in place of the earlier "✓" one (same tag).
     */
    fun matchSms(ctx: Context, store: Store, fresh: List<RawSms>) {
        if (history(store).length() == 0) return
        for (raw in fresh) {
            val p = Parser.parse(raw) ?: continue
            val list = history(store)
            for (i in 0 until list.length()) {
                val x = list.getJSONObject(i)
                if (x.has("sms") || !Ussd.proves(p, x.optString("to"), x.optDouble("a", -1.0), x.optLong("at"))) continue
                val a = p.amount ?: continue
                val to = x.optString("to")
                record(store, x.optString("id"), JSONObject().put("sms", raw.id).put("st", "ok").put("ref", p.txId ?: ""))
                if (x.optString("from") == "iphone" && Sync.isOn(store)) {
                    val text = raw.body.replace(Regex("https?://\\S+"), "").replace(Regex("[ \\t]+"), " ").trim().take(700)
                    try { Sync.notify(ctx, store, listOf(Notifier.Msg("🧾 اتحوّل ${Notifier.fmt(a)} ج لـ $to", text, "x" + x.optString("id")))) } catch (_: Exception) { }
                }
                break
            }
        }
    }

    // ---------- starting one ----------

    /**
     * Starts a transfer. Returns null when it started, or why it can't (said to the user as it is).
     * A transfer that can't start is in the list too, so the iPhone sees why.
     */
    fun start(ctx: Context, toRaw: String, amountRaw: String, pin: String, account: String, from: String, id: String): String? {
        val app = ctx.applicationContext
        val store = Store(app)
        val to = Ussd.mobile(toRaw)
        val amount = Ussd.amount(amountRaw)
        // which wallet: the one picked (by its line); with one Vodafone Cash wallet, that one
        val accs = try { vfAccounts(store.ledger()).map { it.key } } catch (_: Exception) { emptyList() }
        val acct = if (account in accs) account else if (accs.size == 1) accs[0] else null
        val line = acct?.removePrefix(Wallet.VODAFONE.key) ?: ""
        val sub = if (acct != null && phonePerm(app)) subFor(app, store, acct) else -1
        val why = when {
            to == null -> "الرقم مش رقم موبايل مصري صح"
            amount == null -> "المبلغ لازم يكون جنيهات صحيحة من 1 لـ ${Notifier.fmt(Ussd.MAX_AMOUNT.toDouble())}"
            !Ussd.pinOk(pin) -> "الرقم السري لازم يكون أرقام بس"
            !callPerm(app) -> "«محافظي» محتاج إذن المكالمات على الأندرويد علشان يطلب الكود"
            !phonePerm(app) -> "«محافظي» محتاج إذن الهاتف على الأندرويد علشان يختار خط المحفظة"
            !accOn(app) -> "«التحويل التلقائي» مقفول في إمكانية الوصول على الأندرويد"
            acct == null -> if (accs.isEmpty()) "مفيش محفظة فودافون كاش على الموبايل ده" else "اختار الخط اللي هتحوّل منه"
            line.isNotBlank() && sub < 0 -> "مش لاقي شريحة المحفظة دي في الموبايل دلوقتي، فمش هحوّل من خط تاني"
            Ussd.code(template(store), to, amount) == null -> "كود التحويل في الإعدادات مش مظبوط"
            else -> null
        }
        val base = JSONObject().put("to", to ?: toRaw.take(20)).put("a", amount ?: (amountRaw.toLongOrNull() ?: 0)).put("from", from).put("w", acct ?: account)
        if (why != null) { finishEarly(app, store, id, base, why); return why }
        val code = Ussd.code(template(store), to!!, amount!!)!!
        val j: Job
        synchronized(this) {
            val cur = job
            if (cur != null && !cur.done) {
                if (System.currentTimeMillis() - cur.startedAt < TIMEOUT + 10_000L) {
                    val w = "فيه تحويل شغال دلوقتي، استنى يخلص"
                    finishEarly(app, store, id, base, w); return w
                }
                cur.done = true; cur.wipe()
            }
            j = Job(id, to, amount, pin, code, sub, from, acct!!)
            job = j
        }
        record(store, id, base.put("st", "run").put("msg", ""))
        note(app, "⏳ بيحوّل ${Notifier.fmt(amount.toDouble())} ج لـ $to", if (from == "iphone") "طلب من الآيفون" else "من «محافظي»", true)
        refresh(app)
        UssdService.watch(true)
        // our own screen first (it wakes the phone and shows over the lock screen), and it dials;
        // if Android doesn't show it, dial from here
        try {
            app.startActivity(Intent(app, XferActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (_: Exception) { }
        main.postDelayed({ if (job === j && !j.dialed) dial(app, j) }, 2500)
        main.postDelayed({
            if (job === j && !j.done) {
                UssdService.current()?.let { tap(it, false) }   // a Vodafone screen still open: close it, nothing gets typed after this
                end(app, j, "fail", if (j.seen) "فودافون مردتش في الوقت" else "الكود مااتطلبش: لو الموبايل مقفول بباسورد افتحه، أو جرّب تاني")
            }
        }, TIMEOUT)
        return null
    }

    private fun finishEarly(app: Context, store: Store, id: String, base: JSONObject, why: String) {
        record(store, id, JSONObject(base.toString()).put("st", "fail").put("msg", why))
        if (base.optString("from") == "iphone") tellIphone(app, store, "✗ التحويل ماتمش", why, id)
        refresh(app)
    }

    /** Dials the code on the wallet's SIM. */
    @SuppressLint("MissingPermission")   // checked in start(), and a refusal is caught
    fun dial(ctx: Context, j: Job) {
        if (j.dialed || j.done) return
        j.dialed = true
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val extras = Bundle()
            val h = handleFor(ctx, j.sub)
            if (h != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
            else if (j.sub >= 0 && (try { tm.callCapablePhoneAccounts.size } catch (_: Exception) { 2 }) > 1) {
                // two SIMs and this one couldn't be picked: never let the phone dial it on the other line
                end(ctx.applicationContext, j, "fail", "مقدرتش أختار خط المحفظة دي للاتصال، فمش هحوّل من خط تاني"); return
            }
            tm.placeCall(Uri.fromParts("tel", j.code, null), extras)
            j.log("طلب الكود" + if (h != null) " على خط المحفظة" else "")
        } catch (e: Exception) {
            end(ctx.applicationContext, j, "fail", if (e is SecurityException) "«محافظي» محتاج إذن المكالمات" else "مقدرتش أطلب الكود (${e.javaClass.simpleName})")
        }
    }

    /** The SIM of the wallet: "s<id>" lines carry the SIM's id, "1"/"2" its slot. -1 = the phone's default. */
    @SuppressLint("MissingPermission")   // only called with the permission (phonePerm)
    private fun subFor(ctx: Context, store: Store, account: String): Int {
        val line = account.removePrefix(Wallet.VODAFONE.key)
        val sub = when {
            line.startsWith("s") -> line.substring(1).toIntOrNull() ?: -1
            line.toIntOrNull() != null && phonePerm(ctx) -> try {
                ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfoForSimSlotIndex(line.toInt() - 1)?.subscriptionId ?: -1
            } catch (_: Exception) { -1 }
            else -> -1
        }
        return if (SmsApp.valid(sub)) sub else -1
    }

    @SuppressLint("MissingPermission")   // only called with the permission (phonePerm)
    private fun handleFor(ctx: Context, sub: Int): PhoneAccountHandle? {
        if (sub < 0 || !phonePerm(ctx)) return null
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val tel = ctx.getSystemService(TelephonyManager::class.java)
            val all = tm.callCapablePhoneAccounts
            all.firstOrNull { h ->
                val s = if (Build.VERSION.SDK_INT >= 30) try { tel.getSubscriptionId(h) } catch (_: Exception) { -1 } else h.id?.toIntOrNull() ?: -1
                s == sub
            } ?: run {
                val iccid = try { ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(sub)?.iccId } catch (_: Exception) { null }
                if (!iccid.isNullOrBlank()) all.firstOrNull { it.id == iccid || it.id.startsWith(iccid) } else null
            }
        } catch (_: Exception) { null }
    }

    // ---------- each USSD screen ----------

    private val PHONE_PKGS = setOf("com.android.phone", "com.android.server.telecom", "com.android.stk",
        "com.samsung.android.app.telephonyui", "com.google.android.dialer", "com.android.dialer")

    private fun phoneish(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg == ctx.packageName) return false
        if (pkg in PHONE_PKGS) return true
        val p = pkg.lowercase()
        return p.contains("phone") || p.contains("telecom") || p.contains("telephony") || p.contains("dialer") || p.contains("ussd") || p.contains("stk")
    }

    /** The Vodafone screen on display, if there is one: the active window first, then any window from the phone app. */
    private fun ussdRoot(svc: AccessibilityService): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { svc.rootInActiveWindow?.let { roots += it } } catch (_: Exception) { }
        try { for (w in svc.windows) w.root?.let { roots += it } } catch (_: Exception) { }
        return roots.firstOrNull { phoneish(svc, it.packageName?.toString()) }
    }

    private class Seen(val text: String, val input: AccessibilityNodeInfo?)

    /** The words on the screen (not what's typed in the box, not the buttons), and the box to type in. */
    private fun read(root: AccessibilityNodeInfo): Seen {
        val sb = StringBuilder()
        var input: AccessibilityNodeInfo? = null
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || count++ > 400) return
            val cls = n.className?.toString() ?: ""
            if (n.isEditable || cls.contains("EditText")) { if (input == null) input = n; return }
            if (!cls.contains("Button")) n.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        return Seen(sb.toString().trim(), input)
    }

    private val SEND = Regex("^(send|إرسال|ارسال|reply|رد|ok|موافق|حسنا|حسنًا|حسناً|yes|نعم|تم|إرسال الرد)$", RegexOption.IGNORE_CASE)
    private val CANCEL = Regex("^(cancel|إلغاء|الغاء|dismiss|close|إغلاق|اغلاق|خروج|exit)$", RegexOption.IGNORE_CASE)

    private fun button(root: AccessibilityNodeInfo, id: String, words: Regex): AccessibilityNodeInfo? {
        try { root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isEnabled }?.let { return it } } catch (_: Exception) { }
        var found: AccessibilityNodeInfo? = null
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || found != null || depth > 25 || count++ > 400) return
            val t = n.text?.toString()?.trim() ?: ""
            if (t.isNotEmpty() && words.matches(t) && n.isEnabled) { found = if (n.isClickable) n else n.parent ?: n; return }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        return found
    }

    private fun tap(svc: AccessibilityService, positive: Boolean) {
        val root = ussdRoot(svc) ?: return
        val b = if (positive) button(root, "android:id/button1", SEND) else button(root, "android:id/button2", CANCEL)
        val done = try { b?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } catch (_: Exception) { false }
        if (done) return
        // no button found: "send" from the keyboard, or back to close the screen
        if (positive && Build.VERSION.SDK_INT >= 30) {
            val input = read(root).input
            if (input != null && try { input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) } catch (_: Exception) { false }) return
        }
        if (!positive) try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) { }
    }

    private fun type(input: AccessibilityNodeInfo, text: String): Boolean = try {
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    } catch (_: Exception) { false }

    /** Called by [UssdService] for every screen change while a transfer runs. */
    fun onScreen(svc: AccessibilityService) {
        val j = job ?: return
        if (j.done) return
        val root = ussdRoot(svc) ?: return
        val s = read(root)
        if (s.text.isBlank()) return
        // the same screen again (it redraws while we type) is skipped; a new window with the same words is not
        val key = root.windowId.toString() + (if (s.input != null) "?" else "!") + s.text
        if (key == j.last) return
        j.last = key
        j.seen = true
        j.log("شاشة: " + s.text)
        val step = Ussd.step(s.text, s.input != null, j.to, j.pinSent, j.confirmSent, j.pages.toString())
        // a question already answered (from its notification, or this same screen): nothing more to do here
        if (s.input != null && step in ANSWERS && !j.claim(s.text)) return
        when (step) {
            Ussd.Step.WAIT -> { }
            Ussd.Step.MORE -> {
                val key = Ussd.moreKey(s.text)
                if (key == null || j.more >= 4 || !type(s.input!!, key)) { tap(svc, false); end(svc, j, "stop", j.pages.toString() + s.text); return }
                j.more++; j.pages.append(s.text).append('\n')
                j.log("طلب باقي الرسالة ($key)")
                main.postDelayed({ tap(svc, true) }, 350)
            }
            Ussd.Step.PIN -> {
                j.log("كتب الرقم السري في الشاشة")
                val pin = j.takePin()
                if (pin == null || !type(s.input!!, pin)) { tap(svc, false); end(svc, j, "stop", "مقدرتش أكتب الرقم السري في شاشة فودافون"); return }
                j.pinSent = true
                main.postDelayed({ tap(svc, true) }, 350)
            }
            Ussd.Step.CONFIRM -> {
                j.log("أكد بـ 1 في الشاشة")
                if (!type(s.input!!, "1")) { tap(svc, false); end(svc, j, "stop", "مقدرتش أأكد في شاشة فودافون"); return }
                j.confirmSent = true
                main.postDelayed({ tap(svc, true) }, 350)
            }
            Ussd.Step.STOP -> { tap(svc, false); end(svc, j, "stop", j.pages.toString() + s.text) }
            // done: an answer screen is closed with OK; a "done" with a box under it is closed without sending anything
            Ussd.Step.OK -> { tap(svc, s.input == null); end(svc, j, "ok", s.text) }
            Ussd.Step.FAIL -> { tap(svc, true); end(svc, j, "fail", s.text) }
            Ussd.Step.UNSURE -> { tap(svc, true); end(svc, j, "unsure", s.text) }
        }
    }

    // ---------- Vodafone's answer as a notification ----------

    private val ANSWERS = setOf(Ussd.Step.PIN, Ussd.Step.CONFIRM, Ussd.Step.MORE)

    private val VF_WORDS = Regex("فودافون|كاش|محفظ|جنيه|رصيد|تحويل|vodafone|cash|wallet|ussd|mmi|\\b1\\s*[-.)]", RegexOption.IGNORE_CASE)

    /** All the words of a notification. */
    private fun noteText(n: Notification): String {
        val e = n.extras ?: return ""
        val parts = LinkedHashSet<String>()
        for (k in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUMMARY_TEXT))
            e.getCharSequence(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }?.let { s -> parts += s } }
        // a longer text holds the shorter one: keep the longest of each
        return parts.filter { p -> parts.none { it != p && it.contains(p) } }.joinToString("\n")
    }

    private fun send(ctx: Context, pi: PendingIntent?, fill: Intent?): Boolean {
        if (pi == null) return false
        return try {
            val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            pi.send(ctx, 0, fill, null, null, null, opts); true
        } catch (_: Exception) { false }
    }

    /** Types [value] into the notification's own reply box (the one that shows under "رد"), and sends it. */
    private fun reply(ctx: Context, a: Notification.Action, value: String): Boolean {
        val inputs = a.remoteInputs ?: return false
        val results = Bundle().apply { for (r in inputs) putCharSequence(r.resultKey, value) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return send(ctx, a.actionIntent, fill)
    }

    /**
     * Some phones show Vodafone's question as a notification instead of a box on the screen (it pops up at
     * the top and goes into the notification list). Read the same way as a screen: the PIN or the
     * confirmation goes into the notification's reply box; with no reply box, the notification is opened so
     * the box shows and [onScreen] answers it; a final answer ends the transfer. Called from [NoteListener]
     * and [UssdService], whichever hears it first.
     */
    fun onNote(ctx: Context, pkg: String?, n: Notification?) {
        val j = job ?: return
        if (j.done || n == null || !phoneish(ctx, pkg)) return
        if (n.category == Notification.CATEGORY_CALL || n.category == "missed_call") return   // a call, not Vodafone's answer
        val text = noteText(n)
        if (text.isBlank()) return
        val key = text.replace(Regex("\\s+"), " ").trim()
        if (key == j.lastNote) return
        j.lastNote = key
        j.seen = true
        val replyAction = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        j.log("إشعار" + (if (replyAction != null) " فيه رد" else "") + ": " + text)
        if (Ussd.asks(text) || Ussd.moreKey(text) != null) {
            if (!j.claim(text)) return
            if (replyAction == null) {
                // no reply box in it: open it, so Vodafone's box shows on the screen
                j.log("فتح الإشعار")
                if (!send(ctx, n.contentIntent, null)) j.log("مقدرش يفتح الإشعار")
                return
            }
            when (Ussd.step(text, true, j.to, j.pinSent, j.confirmSent, j.pages.toString())) {
                Ussd.Step.OK -> end(ctx, j, "ok", text)   // Vodafone's "done", with "0:مزيد" for its ads
                Ussd.Step.MORE -> {
                    val key = Ussd.moreKey(text)
                    if (key == null || j.more >= 4 || !reply(ctx, replyAction, key)) { end(ctx, j, "stop", j.pages.toString() + text); return }
                    j.more++; j.pages.append(text).append('\n'); j.log("طلب باقي الرسالة من الإشعار ($key)")
                }
                Ussd.Step.PIN -> {
                    val pin = j.takePin()
                    if (pin == null || !reply(ctx, replyAction, pin)) { end(ctx, j, "stop", "مقدرتش أكتب الرقم السري في إشعار فودافون"); return }
                    j.pinSent = true; j.log("رد على الإشعار بالرقم السري")
                }
                Ussd.Step.CONFIRM -> {
                    if (!reply(ctx, replyAction, "1")) { end(ctx, j, "stop", "مقدرتش أأكد في إشعار فودافون"); return }
                    j.confirmSent = true; j.log("رد على الإشعار بـ 1")
                }
                else -> end(ctx, j, "stop", j.pages.toString() + text)   // a second PIN question, or a confirmation for another number
            }
            return
        }
        when (Ussd.step(text, false, j.to, j.pinSent, j.confirmSent)) {
            Ussd.Step.OK -> end(ctx, j, "ok", text)
            Ussd.Step.FAIL -> end(ctx, j, "fail", text)
            Ussd.Step.WAIT -> { }   // "running…"
            // a menu, or words it doesn't know but clearly Vodafone's: open it so it shows on the screen, and keep waiting
            else -> if (VF_WORDS.containsMatchIn(text)) { j.log("فتح الإشعار"); send(ctx, n.contentIntent, null) }
        }
    }

    /** The transfer is over: the list, this phone's notification, the iPhone. */
    private fun end(ctx: Context, j: Job, st: String, text: String) {
        synchronized(this) { if (j.done) return; j.done = true }
        j.wipe()
        UssdService.watch(false)
        XferActivity.close()
        val app = ctx.applicationContext
        val msg = text.replace(Regex("\\s+"), " ").trim().take(400)
        val amt = Notifier.fmt(j.amount.toDouble())
        val title = when (st) {
            "ok" -> "✓ اتحوّل $amt ج لـ ${j.to}"
            "unsure" -> "تحويل $amt ج لـ ${j.to}: شوف ردّ فودافون"
            "stop" -> "✋ وقفت تحويل $amt ج لـ ${j.to}"
            else -> "✗ تحويل $amt ج لـ ${j.to} ماتمش"
        }
        Thread {
            val store = Store(app)
            // Vodafone's SMS already came for it: it went through, whatever the last screen said
            val h = history(store)
            val proven = (0 until h.length()).any { h.getJSONObject(it).let { o -> o.optString("id") == j.id && o.has("sms") } }
            record(store, j.id, JSONObject().put("st", if (proven) "ok" else st).put("msg", msg)
                .put("trace", JSONArray(synchronized(j.trace) { j.trace.toList() })))
            note(app, if (proven) "✓ اتحوّل $amt ج لـ ${j.to}" else title, msg, false)
            // the iPhone already has Vodafone's SMS text for it (matchSms): no second notification over it
            if (j.from == "iphone" && !proven) tellIphone(app, store, title, msg, j.id)
            refresh(app)
        }.start()
    }

    private fun tellIphone(app: Context, store: Store, title: String, text: String, id: String) {
        if (!Sync.isOn(store)) return
        Thread { try { Sync.notify(app, store, listOf(Notifier.Msg(title, text, "x$id"))) } catch (_: Exception) { } }.start()
    }

    private fun refresh(app: Context) {
        MainActivity.refreshIfOpen()
        Thread { try { val s = Store(app); Sync.pushIfOn(app, s) } catch (_: Exception) { } }.start()
    }

    private fun note(ctx: Context, title: String, text: String, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(
            NotificationChannel(CH, "التحويلات من التطبيق", NotificationManager.IMPORTANCE_HIGH).apply { description = "تحويل فودافون كاش من «محافظي» أو من الآيفون" })
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE, Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat).setColor(0xFF1E5AA8.toInt())
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing)
            .build())
    }
}

/**
 * «التحويل التلقائي» in Android's accessibility settings. It looks at the screen only while a transfer
 * runs, and only at the phone app's USSD screens; the rest of the time it only listens for notifications
 * (a second signal, besides [NoteListener], that a new SMS landed).
 */
class UssdService : AccessibilityService() {
    companion object {
        @Volatile private var self: WeakReference<UssdService>? = null
        val on: Boolean get() = self?.get() != null
        fun current(): UssdService? = self?.get()

        /** Screens too while a transfer runs; only notifications the rest of the time. */
        fun watch(screens: Boolean) { self?.get()?.let { s -> Handler(Looper.getMainLooper()).post { s.events(screens) } } }
    }

    private fun events(screens: Boolean) {
        try {
            val i = serviceInfo ?: return
            i.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                (if (screens) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0)
            i.flags = i.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = i
        } catch (_: Exception) { }
    }

    override fun onServiceConnected() {
        self = WeakReference(this)
        val j = Xfer.job
        events(j != null && !j.done)
        WatchService.start(this)
        MainActivity.refreshIfOpen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val n = e.parcelableData as? Notification
            // a transfer is running and the phone shows Vodafone's answer as a notification
            Xfer.job?.let { j -> if (!j.done) try { Xfer.onNote(this, e.packageName?.toString(), n) } catch (_: Exception) { } }
            if (!NoteListener.connected && NoteListener.maybeSms(this, e.packageName?.toString(), n)) { WatchService.saw("acc"); Poke.now(this, "acc") }
            return
        }
        val j = Xfer.job ?: return
        if (j.done) return
        try { Xfer.onScreen(this) } catch (_: Exception) { }
    }

    override fun onInterrupt() { }

    override fun onUnbind(intent: Intent?): Boolean {
        if (self?.get() === this) self = null
        MainActivity.refreshIfOpen()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (self?.get() === this) self = null
        super.onDestroy()
    }
}

/**
 * A see-through screen for the few seconds of a transfer: it wakes the phone and shows over the lock
 * screen, so Vodafone's answer shows on top of it (a closed app can't open the phone's USSD screen
 * otherwise), then dials. It closes itself when the transfer is over.
 */
class XferActivity : Activity() {
    companion object {
        @Volatile private var cur: WeakReference<XferActivity>? = null
        fun close() { cur?.get()?.let { a -> a.runOnUiThread { a.finish() } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cur = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val kg = getSystemService(KeyguardManager::class.java)
        // a lock screen with no password goes away by itself; a locked one stays (the USSD screen still shows over this)
        if (kg != null && kg.isKeyguardLocked && !kg.isDeviceSecure) try { kg.requestDismissKeyguard(this, null) } catch (_: Exception) { }
        val j = Xfer.job
        setContentView(TextView(this).apply {
            text = if (j != null) "⏳ «محافظي» بيحوّل ${Notifier.fmt(j.amount.toDouble())} ج لـ ${j.to}\nاستنى ثواني لحد ما فودافون ترد" else ""
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC16181B.toInt())
            setPadding(48, 32, 48, 32)
            gravity = Gravity.CENTER
        })
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        if (j == null || j.done) { finish(); return }
        Handler(Looper.getMainLooper()).postDelayed({ Xfer.job?.let { if (it === j) Xfer.dial(this, it) } }, 400)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 90_000L)
    }

    override fun onDestroy() {
        if (cur?.get() === this) cur = null
        super.onDestroy()
    }
}
