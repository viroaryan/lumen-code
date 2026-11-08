// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.MainActivity
import app.lumen.R
import app.lumen.engine.Guardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps downloads going when you leave the app. While anything is downloading it shows a quiet
 * progress notification ("Downloading 12 of 300"), which is what lets Android keep the work alive;
 * it stops itself the moment the queue is empty.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + Guardian.handler)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(0, Graph.downloads.pendingCount()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { Guardian.record("download service start", it) }.isSuccess
        // If Android refuses (started from the background), downloads still run while the app is alive.
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true
            scope.launch {
                combine(Graph.downloads.progress, Graph.downloads.completedThisRun) { progress, done -> progress.size to done }
                    .distinctUntilChanged()
                    .collect { (active, done) ->
                        val left = Graph.downloads.pendingCount()
                        if (active == 0 && left == 0) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(done, left)) }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps background data work; when time is up, stop politely. Pending songs resume later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(done: Int, left: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_lumen)
        .setContentTitle(if (left > 0) "Downloading $left ${if (left == 1) "song" else "songs"}" else "Finishing downloads")
        .setContentText(if (done > 0) "$done done so far · you can keep using your phone" else "Songs will play offline when done")
        .setProgress(0, 0, left > 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        .build()

    companion object {
        private const val CHANNEL = "lumen_downloads"
        private const val NOTIFICATION_ID = 4343

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while songs download for offline listening"
                },
            )
        }

        fun start(context: Context) {
            // Android only allows starting this while the app is on screen; otherwise downloads simply
            // run while the process lives and resume from the saved queue next time.
            if (!Guardian.appInForeground()) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { Guardian.record("download service", it) }
        }
    }
}
