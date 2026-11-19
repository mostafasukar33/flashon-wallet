package com.flashon.wallet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.flashon.wallet.core.Limits
import com.flashon.wallet.core.TxType
import org.json.JSONObject
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    companion object {
        private var current: WeakReference<MainActivity>? = null
        fun refreshIfOpen() { current?.get()?.reloadUi() }
        /** An SMS came or went out while محافظي is the SMS app: the messages screens reload. */
        fun smsChanged() { current?.get()?.js("window.App&&App.onSmsChanged&&App.onSmsChanged()") }
        /** The conversation on the screen right now (no notification for it). */
        @Volatile private var viewingThread = ""
        @Volatile private var resumed = false
        fun viewing(thread: Long) = resumed && thread > 0 && viewingThread == thread.toString()
        private const val REQ_SMS = 11
        private const val REQ_NOTE = 12
        private const val REQ_ROLE = 13
        private const val REQ_CONTACTS = 14
        private const val REQ_SEND = 15
        private const val REQ_CALL = 16
    }

    private lateinit var web: WebView
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var ready = false
    /** A conversation to open once the screens are loaded (from a notification, or another app's "send SMS"). */
    private var pendingChat: JSONObject? = null

    fun js(code: String) { main.post { if (::web.isInitialized) web.evaluateJavascript(code, null) } }

    /** What conversation an intent asks for: a notification of ours, or sms:/smsto: from another app. */
    private fun chatFrom(i: Intent?): JSONObject? {
        if (i == null) return null
        if (i.action == SmsApp.ACTION_CHAT) {
            val t = i.getLongExtra("thread", -1)
            return JSONObject().put("id", if (t > 0) t.toString() else "").put("addr", i.getStringExtra("addr") ?: "").put("body", "")
        }
        if (i.action != Intent.ACTION_SENDTO && i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_VIEW) return null
        val d = i.data ?: return null
        if (d.scheme?.lowercase() !in setOf("sms", "smsto", "mms", "mmsto")) return null
        var to = d.schemeSpecificPart ?: ""
        var body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val q = to.indexOf('?')
        if (q >= 0) {
            val query = to.substring(q + 1)
            to = to.substring(0, q)
            if (body.isEmpty()) Regex("(?:^|&)body=([^&]*)").find(query)?.let { body = Uri.decode(it.groupValues[1]) }
        }
        val addr = to.removePrefix("//").split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return JSONObject().put("id", "").put("addr", addr).put("body", body)
    }

    private fun openChat(c: JSONObject) {
        if (ready) js("window.App&&App.openChat(" + JSONObject.quote(c.toString()) + ")") else pendingChat = c
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && id != -1L) {
                if (!Updater.install(this@MainActivity, id)) openApkInBrowser()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        current = WeakReference(this)

        web = WebView(this)
        web.setBackgroundColor(0xFFF4F5F2.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.textZoom = 100
        web.settings.setSupportZoom(false)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pendingChat?.let { pendingChat = null; openChat(it) }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/web/index.html")
        pendingChat = chatFrom(intent)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadDone, filter)
        }

        Notifier.scheduleMonthStart(this)
    }

    private var askedNotif = false
    /** Android 13+: without this permission the app shows nothing, not even the "محافظي شغال" line. Asked once per opening. */
    private fun askNotifications() {
        if (!askedNotif && SmsSource.hasPermission(this) && checkSelfPermission(Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true; requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQ_NOTE); return
        }
        if (askedNotif || Build.VERSION.SDK_INT < 33 || !SmsSource.hasPermission(this)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        askedNotif = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatFrom(intent)?.let { openChat(it) }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // pick up anything missed while the app was closed, then redraw
        WatchService.start(this)
        NoteListener.rebind(this)
        askNotifications()
        Thread {
            try { SmsSource.catchUp(this, "open") } catch (_: Exception) { }   // what came while closed; the other phone hears about it too
            reloadUi()
            if (store.getStr("sync_pending") != null) try { Sync.flushPending(store) } catch (_: Exception) { }
            if (Updater.shouldAutoCheck(this)) { Updater.check(this); reloadUi() }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        Freeze.recordBackground(this)   // for the server log: does Android still count it as running once it's off the screen
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadDone) } catch (_: Exception) { }
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // Building the screen data happens off the main thread; requests that arrive while one is
    // running are merged into a single follow-up build.
    private val building = java.util.concurrent.atomic.AtomicBoolean(false)
    private val again = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reloadUi() {
        again.set(true)
        if (!building.compareAndSet(false, true)) return
        Thread {
            try {
                while (again.getAndSet(false)) {
                    val json = try { DataJson.build(this, store) } catch (_: Exception) { null } ?: continue
                    store.saveScreen(json)
                    val q = JSONObject.quote(json)
                    main.post { if (::web.isInitialized) web.evaluateJavascript("window.App&&App.setData($q)", null) }
                    if (Sync.isOn(store)) Thread { try { Sync.pushIfOn(this, store, json) } catch (_: Exception) { } }.start()
                }
            } finally {
                building.set(false)
                if (again.get()) reloadUi()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.App?App.back():false") { r ->
            if (r != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    /** Back from Android's "make محافظي the SMS app?" question. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ROLE) return
        roleAnswered()
    }

    private fun roleAnswered() {
        val ok = SmsApp.isDefault(this)
        if (ok) {
            TickReceiver.next(this)   // stops the ⏰ every minute
            // names instead of numbers in the messages screen
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
        reloadUi()
        js("window.App&&App.onSmsApp&&App.onSmsApp($ok)")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS) { smsChanged(); return }
        if (requestCode == REQ_CALL) { reloadUi(); return }
        if (requestCode == REQ_SEND) { js("window.App&&App.onSendPerm&&App.onSendPerm(" + (checkSelfPermission(Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) + ")"); return }
        if (requestCode == REQ_NOTE) { WatchService.start(this); reloadUi(); return }   // shows the "محافظي شغال" line now
        if (requestCode == REQ_SMS) {
            if (!SmsSource.hasPermission(this)) {
                Toast.makeText(this, "من غير الإذن ده التطبيق مش هيقدر يقرا الرسايل", Toast.LENGTH_LONG).show()
            }
            WatchService.start(this)
            Thread {
                SmsSource.scanInbox(this)
                Notifier.check(this, store)
                reloadUi()
            }.start()
        }
    }

    private fun openDefaultAppsNow() {
        for (i in listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
    }

    private fun openAppInfoNow() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    private fun openApkInBrowser() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL))) } catch (_: Exception) { }
    }

    /** Functions the screens call. */
    inner class Bridge {
        @JavascriptInterface
        fun getData(): String = DataJson.build(this@MainActivity, store)

        /** The last screen, saved on the phone, so the app shows something instantly. */
        @JavascriptInterface
        fun getCached(): String = store.cachedScreen() ?: ""

        /** Rebuild the screen data in the background; arrives through App.setData. */
        @JavascriptInterface
        fun refresh() = reloadUi()

        /** The full text of one message, only when the user opens it. */
        @JavascriptInterface
        fun getBody(id: String): String = store.allSms().firstOrNull { it.id == id }?.body ?: ""

        @JavascriptInterface
        fun requestSms() {
            main.post {
                val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(perms.toTypedArray(), REQ_SMS)
            }
        }

        @JavascriptInterface
        fun setType(id: String, type: String, learn: Boolean) {
            val t = TxType.fromKey(type) ?: return
            store.setOverride(id, t)
            if (learn) {
                store.ledger().all.firstOrNull { it.sms.id == id }?.let { store.setRule(it.signature, t) }
            }
            Notifier.check(this@MainActivity, store)
            reloadUi()
        }

        @JavascriptInterface
        fun setName(phone: String, name: String) {
            store.setName(phone, name)
            reloadUi()
        }

        @JavascriptInterface
        fun setAccountName(key: String, name: String) {
            store.setAccountName(key, name)
            reloadUi()
        }

        @JavascriptInterface
        fun setLines(json: String) {
            try {
                if (json.isBlank() || json == "null") store.setLines(null)
                else {
                    val o = JSONObject(json); val m = LinkedHashMap<String, String>()
                    for (k in o.keys()) m[k] = o.optString(k)
                    store.setLines(m)
                }
            } catch (_: Exception) { }
            reloadUi()
        }

        @JavascriptInterface
        fun setAccountLimits(key: String, json: String) {
            try { store.setAccountLimits(key, if (json.isBlank() || json == "null") null else JSONObject(json)) } catch (_: Exception) { }
            Notifier.check(this@MainActivity, store)
            reloadUi()
        }

        @JavascriptInterface
        fun setTxNotify(on: Boolean) { store.txNotify = on; reloadUi() }

        /** Watch from another phone: on = new link, off = stop and wipe the copy on the server. */
        @JavascriptInterface
        fun setSync(on: Boolean, password: String) {
            if (on && password.length < 6) return
            if (!on) { store.putLong("xfer_remote", 0); Remote.stop(this@MainActivity) }   // no link = no transfers from the iPhone
            Thread {
                if (on) Sync.enable(this@MainActivity, store, password) else Sync.disable(store)
                reloadUi()
                main.post {
                    val msg = if (!on) "اللينك اتقفل" else if (store.getStr("sync_err") != null) "اللينك جاهز، بس الرفع مستني النت" else "اللينك جاهز ✓"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        @JavascriptInterface
        fun shareSync() {
            val link = Sync.link(store) ?: return
            main.post {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                try { startActivity(Intent.createChooser(i, "ابعت اللينك")) } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun copySync() {
            val link = Sync.link(store) ?: return
            main.post {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("محافظي", link))
                Toast.makeText(this@MainActivity, "اتنسخ", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun exportMonth(month: String, account: String) {
            Thread {
                val i = Export.month(this@MainActivity, store, month, account)
                main.post {
                    if (i == null) Toast.makeText(this@MainActivity, "مقدرتش أعمل الملف", Toast.LENGTH_SHORT).show()
                    else try { startActivity(i) } catch (_: Exception) { }
                }
            }.start()
        }

        @JavascriptInterface
        fun saveSettings(json: String) {
            try {
                val o = JSONObject(json)
                store.saveLimits(Limits(
                    monthReceive = o.optDouble("monthReceive", 200000.0),
                    monthSend = o.optDouble("monthSend", 200000.0),
                    daySend = o.optDouble("daySend", 60000.0),
                    dayReceiveOn = o.optBoolean("dayReceiveOn", false),
                    dayReceive = o.optDouble("dayReceive", 60000.0),
                    feesCount = o.optBoolean("feesCount", true)
                ))
            } catch (_: Exception) { }
            reloadUi()
        }

        @JavascriptInterface
        fun openBattery() {
            main.post {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) { }
                }
            }
        }

        /**
         * The app's own battery page. On Infinix / Tecno (XOS, HiOS) that's where "Allow background
         * activity" and "Stop app when idle" are: the second one is what freezes the app while it's
         * closed, so no SMS reaches it until it's opened. Falls back to the app's settings page.
         */
        @JavascriptInterface
        fun openAppBattery() {
            main.post {
                val pkg = Uri.parse("package:$packageName")
                val tries = listOf(
                    Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").setData(pkg),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                for (i in tries) {
                    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (_: Exception) { }
                }
            }
        }

        /** The phone's own battery saving screen (sleep optimisation, automatic power saving). */
        @JavascriptInterface
        fun openPowerSaving() {
            main.post {
                for (a in listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)) {
                    try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (_: Exception) { }
                }
            }
        }

        /**
         * "Start by itself" (auto-start) screen: Infinix/Tecno/itel (Phone Master), then Xiaomi, Oppo/Realme,
         * Vivo, Huawei; any other phone gets the app's own settings page.
         */
        @JavascriptInterface
        fun openAutostart() {
            main.post {
                val tries = listOf(
                    ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                    ComponentName("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                for (c in tries) {
                    try { startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (_: Exception) { }
                }
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
        }

        /** Android's question "make محافظي the SMS app?" (the fix for a phone that freezes it). */
        @JavascriptInterface
        fun askSmsApp() {
            main.post {
                if (SmsApp.isDefault(this@MainActivity)) { roleAnswered(); return@post }
                val i = SmsApp.requestIntent(this@MainActivity)
                try {
                    if (i == null) throw IllegalStateException()
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_ROLE)
                } catch (_: Exception) {
                    openDefaultAppsNow()
                    js("window.App&&App.onSmsApp&&App.onSmsApp(false)")
                }
            }
        }

        /** The phone's default apps page (pick the SMS app by hand, or go back to the old one). */
        @JavascriptInterface
        fun openDefaultApps() { main.post { openDefaultAppsNow() } }

        /** The app's info page: from there ⋮ → "Allow restricted settings" when Android blocks the SMS app question,
         *  notification access or «التحويل التلقائي» for an app installed from outside the store. */
        @JavascriptInterface
        fun openAppInfo() { main.post { openAppInfoNow() } }

        // ---------- notification access (the fix that keeps the phone's SMS app) ----------

        /** Android's page with the «محافظي» switch for notification access. */
        @JavascriptInterface
        fun openNoteAccess() {
            main.post {
                for (i in NoteListener.settingsIntents(this@MainActivity)) {
                    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (_: Exception) { }
                }
                openAppInfoNow()
            }
        }

        // ---------- Vodafone Cash transfers by code ----------

        /** "Make and manage phone calls": dialing the code, and picking the wallet's SIM. */
        @JavascriptInterface
        fun askCallPerm() {
            main.post { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), REQ_CALL) }
        }

        /** «التحويل التلقائي» in Android's accessibility settings (its own page when the phone has one). */
        @JavascriptInterface
        fun openAccessibility() {
            main.post {
                val tries = ArrayList<Intent>()
                if (Build.VERSION.SDK_INT >= 31) tries += Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, Xfer.accComponent(this@MainActivity).flattenToString())
                tries += Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                for (i in tries) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@post } catch (_: Exception) { }
            }
        }

        /** Transfers asked from the iPhone: on or off (needs the link on). */
        @JavascriptInterface
        fun setXferRemote(on: Boolean) {
            store.putLong("xfer_remote", if (on && Sync.isOn(store)) 1 else 0)
            if (on) Remote.keep(this@MainActivity) else Remote.stop(this@MainActivity)
            reloadUi()
        }

        /** The transfer code, with {n} for the number and {a} for the amount. Empty = back to the usual one. */
        @JavascriptInterface
        fun setXferCode(code: String): Boolean {
            val c = code.trim().replace(" ", "")
            if (c.isNotEmpty() && com.flashon.wallet.core.Ussd.code(c, "01000000000", 1) == null) return false
            if (c.isEmpty() || c == com.flashon.wallet.core.Ussd.DEFAULT_VF) store.remove("xfer_code") else store.putStr("xfer_code", c)
            reloadUi()
            return true
        }

        /** A transfer from this phone's screen: {to, a, pin, w}. The answer comes in App.onXferSent. */
        @JavascriptInterface
        fun sendXfer(json: String) {
            val o = try { JSONObject(json) } catch (_: Exception) { return }
            Thread {
                val why = try {
                    Xfer.start(this@MainActivity, o.optString("to"), o.optString("a"), o.optString("pin"), o.optString("w"), "phone", Xfer.newId())
                } catch (e: Exception) { "حصلت مشكلة: " + e.javaClass.simpleName }
                js("window.App&&App.onXferSent&&App.onXferSent(" + (why == null) + "," + JSONObject.quote(why ?: "") + ")")
            }.start()
        }

        /** Without «التحويل التلقائي»: opens the dialer with the code written, and the user types the PIN in Vodafone's screen. */
        @JavascriptInterface
        fun dialXfer(to: String, amount: String) {
            val n = com.flashon.wallet.core.Ussd.mobile(to) ?: return
            val a = com.flashon.wallet.core.Ussd.amount(amount) ?: return
            val code = com.flashon.wallet.core.Ussd.code(Xfer.template(store), n, a) ?: return
            main.post { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", code, null))) } catch (_: Exception) { } }
        }

        /** Conversations, arrives through App.onThreads. */
        @JavascriptInterface
        fun loadThreads() {
            Thread {
                val j = try { SmsApp.threads(this@MainActivity, store).toString() } catch (_: Exception) { "{\"threads\":[],\"unread\":0}" }
                js("window.App&&App.onThreads(" + JSONObject.quote(j) + ")")
            }.start()
        }

        /** One conversation (marked read), arrives through App.onChat. */
        @JavascriptInterface
        fun loadChat(id: String, addr: String) {
            viewingThread = id
            Thread {
                val j = try { SmsApp.chat(this@MainActivity, store, id.toLongOrNull() ?: -1L, addr) } catch (_: Exception) { null } ?: return@Thread
                if (viewingThread == id || viewingThread.isEmpty()) viewingThread = j.optString("id")
                js("window.App&&App.onChat(" + JSONObject.quote(j.toString()) + ")")
            }.start()
        }

        /** Removes a failed SMS before sending it again. */
        @JavascriptInterface
        fun deleteSms(id: String) { Thread { id.toLongOrNull()?.let { SmsApp.delete(this@MainActivity, it) } }.start() }

        /** Which conversation is on the screen ("" = none). */
        @JavascriptInterface
        fun viewing(id: String) { viewingThread = id }

        @JavascriptInterface
        fun sendSms(addr: String, text: String, sub: Int) {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // the SMS app should have it already; if the phone didn't give it, ask (the text stays in the box)
                main.post { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SEND) }
                js("window.App&&App.onSent(false,'perm')")
                return
            }
            Thread {
                val ok = try { SmsApp.send(this@MainActivity, addr, text, sub) } catch (_: Exception) { false }
                js("window.App&&App.onSent($ok,'')")
            }.start()
        }

        /** The app's notification settings (when the permission was refused for good, only from there). */
        @JavascriptInterface
        fun openNotif() {
            main.post {
                try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { } }
            }
        }

        @JavascriptInterface
        fun rescan() {
            Thread {
                SmsSource.scanInbox(this@MainActivity, full = true)
                reloadUi()
                main.post { Toast.makeText(this@MainActivity, "خلص ✓", Toast.LENGTH_SHORT).show() }
            }.start()
        }

        @JavascriptInterface
        fun checkUpdate() {
            Thread {
                val r = Updater.check(this@MainActivity).toString()
                main.post { web.evaluateJavascript("App.onUpdate(" + JSONObject.quote(r) + ")", null) }
            }.start()
        }

        @JavascriptInterface
        fun downloadUpdate() {
            main.post {
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(this@MainActivity, "اسمح للتطبيق يسطّب التحديثات، وارجع دوس نزّله تاني", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: Exception) { openApkInBrowser() }
                    return@post
                }
                try { downloadId = Updater.download(this@MainActivity) } catch (_: Exception) { openApkInBrowser() }
            }
        }
    }
}
