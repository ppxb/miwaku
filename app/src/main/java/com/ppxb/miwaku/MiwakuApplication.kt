package com.ppxb.miwaku

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.memoryCacheMaxSizePercentWhileInBackground
import com.ppxb.miwaku.core.data.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiwakuApplication : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One memory cache and one disk cache shared by both loaders, so splitting the decoder budget
     * does not double the RAM budget.
     */
    private val memoryCacheBinary by lazy {
        val bytes =
            minOf((Runtime.getRuntime().maxMemory() * MEMORY_CACHE_PERCENT).toLong(), MAX_MEMORY_CACHE_BYTES)
        MemoryCache.Builder().maxSizeBytes(bytes).build()
    }
    private val diskCacheBinary by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("image_cache"))
            .maxSizeBytes(DISK_CACHE_BYTES)
            .build()
    }

    /**
     * Pages decode at most [PAGE_DECODERS] at a time: webtoon pages are tall (a 720×4096 ARGB page is
     * ~11.8 MB), so 3 is the measured-safe ceiling. Downloads go through [PageImageStore] and only
     * decode here.
     */
    val pageImageLoader: ImageLoader by lazy { buildLoader(PAGE_DECODERS) }

    /** Covers are small and numerous; they must not queue behind a page decode. */
    private val coverImageLoader: ImageLoader by lazy { buildLoader(COVER_DECODERS) }

    private fun buildLoader(decoderParallelism: Int): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { memoryCacheBinary }
            .diskCache { diskCacheBinary }
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(decoderParallelism))
            .memoryCacheMaxSizePercentWhileInBackground(0.5)
            .build()

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

    override fun newImageLoader(context: Context): ImageLoader = coverImageLoader

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val MAX_MEMORY_CACHE_BYTES = 128L * 1024 * 1024
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
        const val PAGE_DECODERS = 3
        const val COVER_DECODERS = 4
    }
}
