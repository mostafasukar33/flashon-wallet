package com.flashon.wallet

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.flashon.wallet.core.Wallet
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

object Notifier {
    private const val CH = "limits"

    fun fmt(n: Double): String {
        val r = Math.round(n * 100) / 100.0
        return if (r % 1.0 == 0.0) String.format(Locale.US, "%,d", r.toLong()) else String.format(Locale.US, "%,.2f", r)
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "حدود المحافظ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تنبيهات حدود الاستلام والتحويل"
            })
        }
    }

    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF1E5AA8.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Looks at the limits after a new message and warns once per month/day for each situation. */
    fun check(ctx: Context, store: Store = Store(ctx)) {
        val led = store.ledger()
        val lim = store.limits()
        val month = YearMonth.now().toString()
        val day = LocalDate.now().toString()
        for (w in Wallet.values()) {
            val n = led.now(w)
            if (!n.hasData) continue
            val base = if (w == Wallet.VODAFONE) 100 else 200
            fun once(key: String): Boolean {
                val k = "n_${key}_${w.key}"
                val stamp = if (key.startsWith("day")) day else month
                if (store.getStr(k) == stamp) return false
                store.putStr(k, stamp); return true
            }
            // receive limit
            if (n.monthReceived >= lim.monthReceive && lim.monthReceive > 0) {
                if (once("rcv100")) post(ctx, base + 1, "${w.label}: قفلت حد الاستلام", "استلمت ${fmt(n.monthReceived)} الشهر ده. مش هتقدر تستلم تاني لحد أول الشهر الجاي.")
            } else if (n.monthReceived >= lim.monthReceive * 0.75 && lim.monthReceive > 0) {
                if (once("rcv75")) post(ctx, base + 2, "${w.label}: قربت من حد الاستلام", "فاضلك ${fmt(n.receiveLeft)} بس تستلمهم الشهر ده.")
            }
            // money that will be locked until next month
            val lk = "n_lock_${w.key}_$month"
            val last = store.getDouble(lk)
            if (n.lockedNextMonth > 0.5 && n.lockedNextMonth > last + 0.5) {
                store.putDouble(lk, n.lockedNextMonth)
                post(ctx, base + 3, "${w.label}: ${fmt(n.lockedNextMonth)} ج هيتقفل عليهم",
                    "في المحفظة ${fmt(n.balance ?: 0.0)}، وفاضلك تحوّل ${fmt(n.sendLeftMonth)} بس الشهر ده. الباقي يتحوّل من أول الشهر الجاي.")
            }
            // daily send limit
            if (lim.daySend > 0) {
                if (n.daySent >= lim.daySend) {
                    if (once("day100")) post(ctx, base + 4, "${w.label}: قفلت حد التحويل النهارده", "حوّلت ${fmt(n.daySent)} النهارده. الحد يتجدد الساعة 12 بالليل.")
                } else if (lim.daySend > 10000 && n.daySent >= lim.daySend - 10000) {
                    if (once("day90")) post(ctx, base + 5, "${w.label}: قربت من حد اليوم", "فاضلك ${fmt(n.sendLeftDay)} بس تحوّلهم النهارده.")
                }
            }
            // daily receive limit (only if turned on)
            if (lim.dayReceiveOn && lim.dayReceive > 0 && n.dayReceived >= lim.dayReceive) {
                if (once("dayr100")) post(ctx, base + 6, "${w.label}: قفلت حد الاستلام النهارده", "استلمت ${fmt(n.dayReceived)} النهارده.")
            }
        }
    }

    // ---------- start of month reminder ----------
    fun scheduleMonthStart(ctx: Context) {
        val zone = ZoneId.systemDefault()
        val next = YearMonth.now().plusMonths(1).atDay(1).atTime(LocalTime.of(9, 30)).atZone(zone).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, MonthReceiver::class.java).setAction(MonthReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, next, pi)
    }

    fun monthStarted(ctx: Context) {
        val store = Store(ctx)
        val key = "n_monthstart"
        val m = YearMonth.now().toString()
        if (store.getStr(key) == m) return
        store.putStr(key, m)
        val led = store.ledger()
        val prev = YearMonth.now().minusMonths(1)
        val parts = ArrayList<String>()
        for (w in Wallet.values()) {
            val locked = led.lockedAtEndOf(prev, w)
            if (locked > 0.5) parts += "${fmt(locked)} في ${w.label}"
        }
        val lim = store.limits()
        val text = if (parts.isEmpty()) "تقدر تستلم ${fmt(lim.monthReceive)} وتحوّل ${fmt(lim.monthSend)} الشهر ده."
        else "الفلوس اللي كانت متقفلة بقت متاحة: ${parts.joinToString(" و")}."
        if (led.all.isNotEmpty()) post(ctx, 300, "اتفتح حد الشهر الجديد", text)
    }
}

class MonthReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.flashon.wallet.MONTH_START" }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == ACTION) Notifier.monthStarted(context)
                Notifier.scheduleMonthStart(context)
            } catch (_: Exception) {
            } finally { pending.finish() }
        }.start()
    }
}
