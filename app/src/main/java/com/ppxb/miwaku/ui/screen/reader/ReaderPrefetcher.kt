package com.ppxb.miwaku.ui.screen.reader

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.image.ReaderPerf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Downloads the pages around the viewport, visible page first and the rest in small
 * parallel batches. The visible page is always awaited alone, so the page the reader
 * is looking at never queues behind the prefetch window.
 */
internal class ReaderPrefetcher(
    private val scope: CoroutineScope,
    private val store: PageImageStore,
    private val pages: List<PageImage>,
    private val onPageReady: (PageImage) -> Unit = {},
    private val onPageFailed: (PageImage) -> Unit = {},
) {
    private var job: Job? = null
    private var currentIndex = -1

    fun start(initialIndex: Int) {
        update(initialIndex)
    }

    fun update(index: Int) {
        if (pages.isEmpty()) return
        val target = index.coerceIn(0, pages.lastIndex)
        if (target == currentIndex && job?.isActive == true) return
        currentIndex = target
        job?.cancel()
        job =
            scope.launch {
                val started = ReaderPerf.mark()
                val window = window(target)
                ReaderPerf.log { "prefetch_start index=$target count=${window.size}" }

                val visible = window.firstOrNull() ?: return@launch
                download(visible)
                ReaderPerf.log { "prefetch_visible_done index=$target ms=${ReaderPerf.mark() - started}" }

                for (batch in window.drop(1).chunked(PREFETCH_CONCURRENCY)) {
                    if (!isActive) return@launch
                    coroutineScope {
                        batch.forEach { page ->
                            launch { download(page) }
                        }
                    }
                }
                ReaderPerf.log { "prefetch_done index=$target ms=${ReaderPerf.mark() - started}" }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun download(page: PageImage) {
        try {
            store.prepare(page)
            onPageReady(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderPerf.log { "prefetch_page_failed key=${page.cacheKey} msg=${e.message}" }
            onPageFailed(page)
        }
    }

    private fun window(index: Int): List<PageImage> {
        val window = LinkedHashSet<PageImage>()
        window += pages[index]
        for (offset in 1..AHEAD) {
            pages.getOrNull(index + offset)?.let { window += it }
        }
        for (offset in 1..BEHIND) {
            pages.getOrNull(index - offset)?.let { window += it }
        }
        return window.toList()
    }

    private companion object {
        const val AHEAD = 6
        const val BEHIND = 2
        const val PREFETCH_CONCURRENCY = 3
    }
}
