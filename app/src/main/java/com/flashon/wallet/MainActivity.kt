package com.flashon.wallet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
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
        private const val REQ_SMS = 11
    }

    private lateinit var web: WebView
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var ready = false

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
            override fun onPageFinished(view: WebView?, url: String?) { ready = true }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/web/index.html")

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadDone, filter)
        }

        Notifier.scheduleMonthStart(this)
    }

    override fun onResume() {
        super.onResume()
        // pick up anything missed while the app was closed, then redraw
        Thread {
            SmsSource.scanInbox(this)
            reloadUi()
            if (Updater.shouldAutoCheck(this)) { Updater.check(this); reloadUi() }
        }.start()
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadDone) } catch (_: Exception) { }
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun reloadUi() {
        main.post { if (::web.isInitialized) web.evaluateJavascript("window.App&&App.reload()", null) }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS) {
            if (!SmsSource.hasPermission(this)) {
                Toast.makeText(this, "من غير الإذن ده التطبيق مش هيقدر يقرا الرسايل", Toast.LENGTH_LONG).show()
            }
            Thread {
                SmsSource.scanInbox(this)
                Notifier.check(this, store)
                reloadUi()
            }.start()
        }
    }

    private fun openApkInBrowser() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.APK_URL))) } catch (_: Exception) { }
    }

    /** Functions the screens call. */
    inner class Bridge {
        @JavascriptInterface
        fun getData(): String = DataJson.build(this@MainActivity, store)

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

        @JavascriptInterface
        fun rescan() {
            Thread {
                SmsSource.scanInbox(this@MainActivity)
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
