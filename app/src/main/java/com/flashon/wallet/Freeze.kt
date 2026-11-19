package com.flashon.wallet

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/**
 * Infinix, Tecno and itel (XOS / HiOS) freeze an app that isn't on the screen, even with the
 * foreground service and no battery saving: nothing in it runs (no SMS broadcast, no inbox watcher,
 * not even the exact alarm) until the app is opened, and then everything that waited arrives at once.
 * The server log showed it on an Infinix with Android 16: the process was alive all along
 * (age=943s), the SMS waited 511 seconds, and the alarm and the inbox watcher only fired the second
 * the app was opened. That is the phone's "Stop app when idle" setting, which no Android API can
 * turn off for the user. So this notices it and tells them, with the exact page that fixes it:
 * the alarm rings every minute, so a much longer silence (same boot) means the phone held the app.
 */
object Freeze {
    /** The alarm is every minute: this long without one means the phone held the app. */
    private const val LATE = 5 * 60_000L
    /** This long in one go with the screen off and the app awake = the setting is fixed. */
    const val PROVEN = 20 * 60_000L
    /** A wallet SMS caught this late was missed in real time. */
    private const val MISS = 2 * 60_000L
    private val LOCK = Any()

    fun isTranssion() = Build.MANUFACTURER.lowercase().let { it.contains("infinix") || it.contains("tecno") || it.contains("itel") || it.contains("transsion") }

    /** Called by every tick and every start of the watcher. */
    fun beat(ctx: Context) = synchronized(LOCK) {
        val s = Store(ctx)
        val now = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        val boot = now - el
        val lastE = s.getLong("hb_e")
        val sameBoot = lastE > 0 && el >= lastE && Math.abs(boot - s.getLong("hb_boot")) < 60_000L
        if (sameBoot) {
            val gap = el - lastE
            val screenOn = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Exception) { true }
            if (gap > LATE && !SmsApp.isDefault(ctx)) {   // as the SMS app there's no minute alarm: a long gap means nothing
                s.putLong("frz_n", s.getLong("frz_n") + 1)
                s.putLong("frz_at", now)
                s.putLong("frz_gap", gap)
                s.putLong("off_run", 0); s.putLong("off_best", 0)
            } else if (!screenOn && s.getLong("hb_off") == 1L) {
                // screen was off at the last beat and still is: one more minute awake with the screen off
                val run = s.getLong("off_run") + gap
                s.putLong("off_run", run)
                if (run > s.getLong("off_best")) s.putLong("off_best", run)
            } else {
                s.putLong("off_run", 0)
            }
            s.putLong("hb_off", if (screenOn) 0 else 1)
        } else {
            s.putLong("hb_off", 0); s.putLong("off_run", 0)
        }
        s.putLong("hb_e", el)
        s.putLong("hb_boot", boot)
    }

    /** A new wallet SMS that reached the app [lagMs] after it landed. */
    fun caught(store: Store, lagMs: Long) {
        if (lagMs < MISS) return
        store.putLong("miss_at", System.currentTimeMillis())
        store.putLong("miss_lag", lagMs)
    }

    /** For the screens: did the phone hold the app, for how long, and has it stayed awake since. */
    fun json(store: Store): JSONObject {
        val at = store.getLong("frz_at")
        return JSONObject()
            .put("n", store.getLong("frz_n"))
            .put("at", at)
            .put("gap", store.getLong("frz_gap"))
            .put("best", store.getLong("off_best"))
            .put("proven", PROVEN)
            .put("held", at > 0 && store.getLong("off_best") < PROVEN)
            .put("missAt", store.getLong("miss_at"))
            .put("missLag", store.getLong("miss_lag"))
            .put("tr", isTranssion())
    }

    /**
     * Is the "محافظي شغال" line really showing, and are the app's notification channels on
     * (importance 0 = turned off by the user or the phone, -1 = not made yet).
     */
    fun notes(ctx: Context): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val line = try { nm?.activeNotifications?.any { it.id == WatchService.NOTE_ID } ?: false } catch (_: Exception) { false }
        fun imp(id: String) = try { nm?.getNotificationChannel(id)?.importance ?: -1 } catch (_: Exception) { -1 }
        return JSONObject().put("line", line).put("watch", imp(WatchService.CH)).put("tx", imp("tx")).put("limits", imp("limits"))
    }

    /** How Android ranks the app 5 seconds after it leaves the screen (125 = foreground service, 400 = cached). */
    fun recordBackground(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                Thread.sleep(5000)
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                Store(app).putLong("imp_bg", info.importance.toLong())
            } catch (_: Exception) { }
        }.start()
    }

    /** For the server log: count/last hold in seconds/how long ago; best screen-off minutes awake since. */
    fun diag(store: Store): String {
        val now = System.currentTimeMillis()
        val at = store.getLong("frz_at")
        return ";frz=" + store.getLong("frz_n") + "/" + store.getLong("frz_gap") / 1000 + "/" + (if (at > 0) (now - at) / 1000 else -1) +
            ";offok=" + store.getLong("off_best") / 1000 +
            ";tk=" + WatchService.tickLate / 1000 + "/" + WatchService.tickLateMax / 1000 +
            ";imp=" + store.getLong("imp_bg") +
            ";txn=" + (if (store.txNotify) 1 else 0)
    }

    fun notesDiag(ctx: Context): String = try {
        val n = notes(ctx)
        ";line=" + (if (n.getBoolean("line")) 1 else 0) + ";ch=" + n.getInt("watch") + "," + n.getInt("tx") + "," + n.getInt("limits")
    } catch (_: Exception) { "" }
}
