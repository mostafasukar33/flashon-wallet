package com.flashon.wallet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Wakes the app when the internet is back, to send iPhone notifications that couldn't go out. */
class PushRetry : JobService() {
    companion object {
        private const val JOB = 41
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB) != null) return
            js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, PushRetry::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val store = Store(this)
            val done = try { Sync.flushPending(store) } catch (_: Exception) { false }
            if (done) try { Sync.pushIfOn(this, store) } catch (_: Exception) { }
            jobFinished(params, !done)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
