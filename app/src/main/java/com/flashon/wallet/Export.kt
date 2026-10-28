package com.flashon.wallet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.flashon.wallet.core.TxType
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes a month of operations as a file Excel opens, and hands it to WhatsApp / Drive / email. */
object Export {
    const val AUTH = "com.flashon.wallet.export"

    private fun cell(s: String): String {
        val v = s.replace("\"", "\"\"")
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"$v\"" else v
    }

    private fun num(d: Double?): String = d?.let { String.format(Locale.US, "%.2f", it) } ?: ""

    fun month(ctx: Context, store: Store, month: String, account: String?): Intent? {
        val ym = try { YearMonth.parse(month) } catch (_: Exception) { return null }
        val led = store.ledger()
        val zone = ZoneId.systemDefault()
        val dF = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val tF = DateTimeFormatter.ofPattern("hh:mm a", Locale("ar"))
        val sb = StringBuilder("﻿")
        sb.append(listOf("التاريخ", "الوقت", "المحفظة", "النوع", "العملية", "المبلغ", "الرسوم", "الاسم", "الرقم", "الرصيد بعدها", "رقم العملية").joinToString(",")).append("\r\n")
        var count = 0
        var tin = 0.0; var tout = 0.0; var tfee = 0.0
        for (p in led.txs) {
            val acc = led.account(p)
            if (account != null && account != "all" && acc.key != account) continue
            val z = Instant.ofEpochMilli(p.sms.time).atZone(zone)
            if (YearMonth.from(z) != ym) continue
            val isIn = p.type == TxType.IN
            if (isIn) tin += p.amount ?: 0.0 else { tout += p.amount ?: 0.0; tfee += p.fee }
            sb.append(listOf(
                z.format(dF), z.format(tF), led.accountLabel(acc), if (isIn) "وارد" else "صادر", p.kind,
                num(p.amount), if (p.fee > 0) num(p.fee) else "", led.displayName(p)?.takeIf { it != p.phone } ?: (p.smsName ?: ""),
                p.phone?.let { "'$it" } ?: "", num(p.balance), p.txId?.let { "'$it" } ?: ""
            ).joinToString(",") { cell(it) }).append("\r\n")
            count++
        }
        sb.append("\r\n")
        sb.append(listOf("الإجمالي", "", "", "وارد", "", num(tin)).joinToString(",") { cell(it) }).append("\r\n")
        sb.append(listOf("", "", "", "صادر", "", num(tout), num(tfee)).joinToString(",") { cell(it) }).append("\r\n")

        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "محافظي-$month.csv"
        File(dir, name).writeText(sb.toString(), Charsets.UTF_8)
        val uri = Uri.parse("content://$AUTH/" + Uri.encode(name))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "حساب المحافظ $month")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "ابعت الملف ($count عملية)").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Serves only the exported file, read-only. */
class ExportProvider : ContentProvider() {
    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        val f = File(File(context!!.cacheDir, "export"), name)
        return if (f.exists()) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/csv"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        if (f != null) c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
