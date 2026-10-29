package com.ppxb.miwaku.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ppxb.miwaku.MainActivity
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.download.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the download queue alive while Miwaku is backgrounded or the screen is off.
 * The queue itself still lives in [com.ppxb.miwaku.core.data.download.DownloadManager]; this
 * service only owns the foreground notification and the pause action.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // A new run replaces the previous run's "finished" card.
        NotificationManagerCompat.from(this).cancel(COMPLETION_NOTIFICATION_ID)
        startForegroundCompat(preparingNotification())

        val manager = AppGraph.downloadManager
        val sessionIds = linkedSetOf<String>()
        scope.launch {
            combine(
                manager.tasks,
                manager.isPaused,
                manager.waitingForWifi,
            ) { tasks, paused, waitingForWifi ->
                Triple(tasks, paused, waitingForWifi)
            }.distinctUntilChanged()
                .collect { (tasks, paused, waitingForWifi) ->
                    val active =
                        tasks.filter {
                            it.state == DownloadState.Queued || it.state == DownloadState.Running
                        }
                    if (paused) {
                        stopForegroundCompat()
                        stopSelf()
                        return@collect
                    }
                    if (active.isEmpty()) {
                        // Count every chapter seen active in this service session. A cancelled
                        // chapter disappears from the list, which suppresses the completion notice.
                        val finished =
                            sessionIds.mapNotNull { id ->
                                tasks.firstOrNull { it.chapterId == id }
                            }
                        val naturallyFinished =
                            sessionIds.isNotEmpty() &&
                                finished.size == sessionIds.size &&
                                finished.all {
                                    it.state == DownloadState.Completed ||
                                        it.state == DownloadState.Failed
                                }
                        if (naturallyFinished) notifyCompletion(finished)
                        stopForegroundCompat()
                        stopSelf()
                        return@collect
                    }
                    sessionIds += active.map { it.chapterId }
                    updateNotification(summarize(active, waitingForWifi))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            AppGraph.downloadManager.pauseAll()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // A start can arrive on an instance that already called stopForeground() (pause then
        // immediate resume). AMS still requires this start to enter foreground within 5 seconds.
        if (!foregroundStarted) {
            startForegroundCompat(preparingNotification())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(summary: Summary) {
        if (!canPostNotifications()) return
        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(summary.title)
                .setContentText(summary.text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, getString(R.string.download_notification_pause), pauseIntent())

        if (summary.progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, summary.progress, false)
        }
        if (summary.more != null) {
            builder.setSubText(summary.more)
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun notifyCompletion(finished: List<DownloadTask>) {
        if (!canPostNotifications()) return
        val completed = finished.count { it.state == DownloadState.Completed }
        val failed = finished.count { it.state == DownloadState.Failed }
        if (completed == 0 && failed == 0) return
        val title =
            if (completed == 0) {
                getString(R.string.download_notification_failed)
            } else {
                getString(R.string.download_notification_complete)
            }
        val text =
            if (failed > 0) {
                getString(R.string.download_notification_complete_failed, completed, failed)
            } else {
                getString(R.string.download_notification_complete_count, completed)
            }
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        NotificationManagerCompat.from(this).notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun summarize(active: List<DownloadTask>, waitingForWifi: Boolean): Summary {
        val current = active.firstOrNull { it.state == DownloadState.Running } ?: active.first()
        val title = current.albumTitle.ifBlank { getString(R.string.download_notification_title) }
        val text =
            when {
                waitingForWifi -> getString(R.string.download_notification_waiting_wifi)
                current.totalPages > 0 ->
                    "${current.chapterName} · " +
                        getString(
                            R.string.download_notification_pages,
                            current.donePages,
                            current.totalPages,
                        )
                else -> current.chapterName
            }
        val progress =
            if (!waitingForWifi && current.totalPages > 0) {
                (current.donePages * 100 / current.totalPages).coerceIn(0, 100)
            } else {
                null
            }
        val more =
            if (active.size > 1) {
                getString(R.string.download_notification_more, active.size - 1)
            } else {
                null
            }
        return Summary(title, text, progress, more)
    }

    private fun preparingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_notification_preparing))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.download_notification_pause), pauseIntent())
            .build()

    private fun startForegroundCompat(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_notification_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pauseIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private data class Summary(
        val title: String,
        val text: String,
        val progress: Int?,
        val more: String?,
    )

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val ACTION_PAUSE = "com.ppxb.miwaku.download.PAUSE"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            }.onFailure { failure ->
                if (failure is IllegalStateException) {
                    android.util.Log.w("DownloadService", "foreground start was blocked", failure)
                }
            }
        }
    }
}
