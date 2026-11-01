package com.ppxb.miwaku.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ppxb.miwaku.core.data.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Last-resort wake-up for the download queue.
 *
 * The foreground service is the primary carrier. This worker only covers the cases it cannot: a
 * dataSync start that the system blocks, or a process killed while chapters were still queued.
 * It never unpauses a queue the user paused, and it does not download anything itself — it keeps
 * the process alive while [com.ppxb.miwaku.core.data.download.DownloadManager] drains the queue.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.library.awaitReady()
        val manager = AppGraph.downloadManager
        if (manager.isPaused.value || manager.waitingForWifi.value) return Result.success()
        // The task flow can replay an empty list right after a cold start, so ask the store.
        if (!manager.hasUnfinished()) return Result.success()
        manager.resumeAll()
        val drained =
            withTimeoutOrNull(WORK_TIMEOUT_MS) {
                while (manager.hasUnfinished()) delay(POLL_INTERVAL_MS)
            }
        // Still queued after the window: let WorkManager schedule another run instead of dying.
        return if (drained == null) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "download-queue"
        /** Kept under WorkManager's ten-minute worker limit so a retry can be scheduled cleanly. */
        private const val WORK_TIMEOUT_MS = 9 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 2_000L

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
