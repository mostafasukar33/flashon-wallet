package com.flashon.wallet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Telephony

/**
 * Keeps the app awake so a wallet SMS reaches the other phone the moment it lands, even when the
 * app is closed and swiped away. Phones like Infinix, Tecno, Xiaomi and Oppo don't wake a closed
 * app for an SMS, so the manifest receiver alone never ran there. A foreground service (with its
 * small fixed notification, which Android requires) is not killed, and while it lives it catches
 * every SMS twice over: its own SMS receiver, and a watcher on the inbox itself.
 */
class WatchService : Service() {
    companion object {
        private const val CH = "watch"
        private const val NOTE_ID = 7001
        @Volatile var running = false
            private set

        /** Starts the watcher if SMS reading is allowed. Safe to call any time, from anywhere. */
        fun start(ctx: Context) {
            if (!SmsSource.hasPermission(ctx)) return
            val app = ctx.applicationContext
            try { app.startForegroundService(Intent(app, WatchService::class.java)) } catch (_: Exception) { }
            WatchJob.schedule(app)
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var receiver: BroadcastReceiver? = null
    private var observer: ContentObserver? = null
    private val scan = Runnable { try { SmsSource.catchUp(this, "box") } catch (_: Exception) { } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTE_ID, note())
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (thread == null) watch()
        handler?.post(scan)   // anything that came while the watcher was stopped
        return START_STICKY
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "محافظي شغال", NotificationManager.IMPORTANCE_MIN).apply {
                description = "علشان التحويلات توصل الموبايل التاني في نفس اللحظة"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF1E5AA8.toInt())
            .setContentTitle("💰 محافظي شغال ومستني التحويلات")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun watch() {
        val t = HandlerThread("watch").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // 1) the SMS itself, the moment it lands (works while this service lives, on any phone)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val list = SmsReceiver.parse(context, intent)
                if (list.isEmpty()) return
                // goAsync keeps the phone awake until the send is done (a plain post let it fall asleep first)
                val pending = goAsync()
                Thread { try { SmsSource.deliver(context, list, "watch") } catch (_: Exception) { } finally { pending.finish() } }.start()
            }
        }
        val f = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, android.Manifest.permission.BROADCAST_SMS, h, Context.RECEIVER_EXPORTED)
            else registerReceiver(r, f, android.Manifest.permission.BROADCAST_SMS, h)
            receiver = r
        } catch (_: Exception) { }
        // 2) the inbox: catches it too if the SMS broadcast never came (a second later, once it is saved)
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                SmsSource.awake(this@WatchService, "box", 10_000L)   // stay awake for the short wait below
                h.removeCallbacks(scan); h.postDelayed(scan, 1500)
            }
        }
        try { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o); observer = o } catch (_: Exception) { }
    }

    /** Swiped away from the recent apps: come back by itself a second later. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val pi = PendingIntent.getForegroundService(this, 2, Intent(this, WatchService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi)
        } catch (_: Exception) { }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        try { observer?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) { }
        thread?.quitSafely()
        thread = null; handler = null; receiver = null; observer = null
        super.onDestroy()
    }
}

/** A safety net if the phone stopped the watcher anyway: every 15 minutes (Android's shortest) it reads what came and starts the watcher again. */
class WatchJob : JobService() {
    companion object {
        private const val JOB = 42
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB) != null) return
            try {
                js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, WatchJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build())
            } catch (_: Exception) { }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { SmsSource.catchUp(this, "job") } catch (_: Exception) { }
            if (!WatchService.running) WatchService.start(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
