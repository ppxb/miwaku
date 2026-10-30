package com.ppxb.miwaku

import android.app.Application
import coil3.ImageLoader
import android.content.Context
import coil3.SingletonImageLoader
import coil3.disk.directory
import coil3.memoryCacheMaxSizePercentWhileInBackground
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.ppxb.miwaku.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MiwakuApplication : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader: ImageLoader by lazy {
        val memoryCacheBytes =
            minOf((Runtime.getRuntime().maxMemory() * MEMORY_CACHE_PERCENT).toLong(), MAX_MEMORY_CACHE_BYTES)
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            // Image URLs are content-addressed by album/chapter id, so cache headers only
            // get in the way of keeping covers around.
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(1))
            .memoryCacheMaxSizePercentWhileInBackground(0.5)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Restore queued chapters off the main thread; the manager reads its task list from disk.
        appScope.launch {
            try {
                AppGraph.library.awaitReady()
                AppGraph.downloadManager
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // MainActivity presents the migration failure without discarding the legacy data.
            }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = imageLoader

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val MAX_MEMORY_CACHE_BYTES = 128L * 1024 * 1024
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
