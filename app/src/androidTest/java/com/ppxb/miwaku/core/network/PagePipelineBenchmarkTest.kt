package com.ppxb.miwaku.core.network

import android.app.ActivityManager
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.reader.image.PageImageRequests
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network comparison of the page pipeline budgets, run on a device:
 * transfer pools 3/2/5 vs 4/2/6 vs 6/3/9, then decode parallelism 1/2/3.
 */
@RunWith(AndroidJUnit4::class)
class PagePipelineBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheRoot = File(context.cacheDir, "reader_pages/v1")

    @Test
    fun compareBudgets() = runBlocking {
        val comics = AppGraph.comics
        val detail = comics.detail(ALBUM_ID)
        val chapter = comics.chapter(detail.id, detail.chapters.first().id)
        val images =
            chapter.pages.take(PAGES).mapIndexed { index, page ->
                PageImage(chapter.chapterId, page.fileName, index, page.splitCount, page.url)
            }
        Log.i(TAG, "chapter=${chapter.chapterId} pages=${images.size} split=${images.map { it.splitCount }}")

        for ((readers, downloads, total) in TRANSFER_CONFIGS) {
            cacheRoot.deleteRecursively()
            val store = newStore(readers, downloads, total)
            val firstStart = System.nanoTime()
            val first = runCatching { store.prepare(images.first()) }
            val firstMs = (System.nanoTime() - firstStart) / 1_000_000L
            val burstStart = System.nanoTime()
            val burst = coroutineScope { images.map { async { runCatching { store.prepare(it) } } }.awaitAll() }
            val burstMs = (System.nanoTime() - burstStart) / 1_000_000L
            val failures = burst.count { it.isFailure } + if (first.isFailure) 1 else 0
            Log.i(TAG, "transfer readers=$readers download=$downloads total=$total first=${firstMs}ms burst=${burstMs}ms failures=$failures")
        }

        for (decoders in DECODE_CONFIGS) {
            cacheRoot.deleteRecursively()
            val store = newStore(4, 2, 6)
            coroutineScope { images.map { async { runCatching { store.prepare(it) } } }.awaitAll() }
            val files = images.mapNotNull { image -> store.cachedFile(image)?.takeIf { it.isFile } }
            if (files.size < images.size) Log.w(TAG, "decode: only ${files.size}/${images.size} pages cached")

            val loader =
                ImageLoader.Builder(context)
                    .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(decoders))
                    .memoryCache { MemoryCache.Builder().maxSizeBytes(1).build() }
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()

            val baseline = pssMb()
            var peak = baseline
            val sampler = launch { while (isActive) { peak = maxOf(peak, pssMb()); delay(20) } }
            var decodeMs = 0L
            var failures = 0
            repeat(DECODE_PASSES) {
                val started = System.nanoTime()
                val decoded =
                    coroutineScope {
                        files.mapIndexed { index, file ->
                            async {
                                runCatching {
                                    loader.execute(
                                        PageImageRequests.build(
                                            context = context,
                                            image = images[index],
                                            file = file,
                                            widthPx = 1080,
                                            attempt = 0,
                                        ),
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                decodeMs += (System.nanoTime() - started) / 1_000_000L
                failures += decoded.count { it.isFailure }
            }
            sampler.cancelAndJoin()
            Log.i(
                TAG,
                "decode decoders=$decoders pages=${files.size} passes=$DECODE_PASSES avg=${decodeMs / DECODE_PASSES}ms failures=$failures pssDelta=${peak - baseline}MB",
            )
            loader.shutdown()
        }
    }

    private fun newStore(readers: Int, downloads: Int, total: Int): PageImageStore =
        PageImageStore(
            context = context,
            userAgent = "benchmark",
            httpClient = OkHttpClient(),
            readerTransfers = readers,
            downloadTransfers = downloads,
            totalTransfers = total,
            cacheLimitBytes = { 512L * 1024 * 1024 },
        )

    private suspend fun timeMillis(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private fun pssMb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024L
    }

    private companion object {
        const val TAG = "PageBench"
        const val ALBUM_ID = "1487138"
        const val PAGES = 8
        const val DECODE_PASSES = 5
        val TRANSFER_CONFIGS = listOf(Triple(3, 2, 5), Triple(4, 2, 6), Triple(6, 3, 9))
        val DECODE_CONFIGS = listOf(1, 2, 3)
    }
}
