package com.flashon.wallet

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** New versions are published as GitHub releases named v<number>. */
object Updater {
    private const val REPO = "mostafasukar33/flashon-wallet"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/flashon-wallet.apk"

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return pi.longVersionCode
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1"

    /** Blocking – call from a background thread. Returns {available, version} or {error}. */
    fun check(ctx: Context): JSONObject {
        val res = JSONObject()
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "flashon-wallet")
            if (c.responseCode == 404) return res.put("available", false)
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(body).optString("tag_name")
            val code = tag.trimStart('v', 'V').toLongOrNull() ?: 0L
            val store = Store(ctx)
            store.putLong("upd_checked", System.currentTimeMillis())
            store.putLong("upd_code", code)
            res.put("available", code > currentCode(ctx)).put("version", code.toString())
        } catch (e: Exception) {
            res.put("available", false).put("error", true)
        }
        return res
    }

    fun cached(ctx: Context): JSONObject {
        val code = Store(ctx).getLong("upd_code")
        return JSONObject().put("available", code > currentCode(ctx)).put("version", code.toString())
    }

    fun shouldAutoCheck(ctx: Context) =
        System.currentTimeMillis() - Store(ctx).getLong("upd_checked") > 6 * 60 * 60 * 1000L

    /** Downloads the new APK; MainActivity opens the installer when it finishes. */
    fun download(ctx: Context): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("تحديث محافظي")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "flashon-wallet-${System.currentTimeMillis()}.apk")
        return dm.enqueue(req)
    }

    fun install(ctx: Context, downloadId: Long): Boolean {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return false
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
