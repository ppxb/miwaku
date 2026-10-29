package com.ppxb.miwaku

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ppxb.miwaku.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiwakuApplication : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader: ImageLoader by lazy {
        val memoryCacheBytes =
            minOf((Runtime.getRuntime().maxMemory() * MEMORY_CACHE_PERCENT).toLong(), MAX_MEMORY_CACHE_BYTES)
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryCacheBytes.toInt())
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
            .respectCacheHeaders(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Restore queued chapters off the main thread; the manager reads its task list from disk.
        appScope.launch { AppGraph.downloadManager }
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        imageLoader.memoryCache?.trimMemory(level)
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.25
        const val MAX_MEMORY_CACHE_BYTES = 128L * 1024 * 1024
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    }
}
