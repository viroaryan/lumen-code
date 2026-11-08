// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.engine

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import app.lumen.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's own background worker, run by Android a few times a day (no server involved). Each run:
 * tidies storage, heals the library, keeps the home feed warm on Wi-Fi, and maybe sends one
 * notification picked from your taste.
 */
class LumenJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Guardian.handler)
    /** The run in progress. Stopping cancels only this run, so the service stays usable for the next one. */
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            withTimeoutOrNull(8 * 60 * 1000L) { runMaintenance(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7101
        private const val PERIOD = 90 * 60 * 1000L

        /** Schedules the periodic job once; Android keeps it across reboots. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // Every 90 minutes, so both daily notification windows (day and evening) are reliably reached.
            // A job scheduled by an older version with a longer period is replaced.
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing != null && existing.intervalMillis == PERIOD) return
            if (existing != null) scheduler.cancel(JOB_ID)
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, LumenJobService::class.java))
                .setPeriodic(PERIOD, 30 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { Guardian.record("schedule job", it) }
        }

        suspend fun runMaintenance(context: Context) {
            runCatching { StorageGuard.maintain(context) }.onFailure { Guardian.record("job storage", it) }
            runCatching { Notifier.maybeNotify(context) }.onFailure { Guardian.record("job notify", it) }
            // Fresh feed waiting for the next open, but only on unmetered networks (no mobile data spent).
            val metered = runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
            if (!metered && !Guardian.appInForeground()) {
                runCatching { Graph.home.prefetchQuietly() }.onFailure { Guardian.record("job feed", it) }
            }
        }
    }
}
