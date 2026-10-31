package com.ppxb.miwaku.ui.reader

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.image.PagePriority
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
    private val ahead: Int = 6,
    private val behind: Int = 2,
    private val prefetchAllowed: () -> Boolean = { true },
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
                download(visible.first, visible.second)
                ReaderPerf.log { "prefetch_visible_done index=$target ms=${ReaderPerf.mark() - started}" }

                if (!prefetchAllowed()) {
                    ReaderPerf.log { "prefetch_skipped_metered index=$target" }
                    return@launch
                }

                for (batch in window.drop(1).chunked(PREFETCH_CONCURRENCY)) {
                    if (!isActive) return@launch
                    coroutineScope {
                        batch.forEach { (page, priority) ->
                            launch { download(page, priority) }
                        }
                    }
                }
                ReaderPerf.log { "prefetch_done index=$target ms=${ReaderPerf.mark() - started}" }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun download(page: PageImage, priority: PagePriority) {
        try {
            store.prepare(page, priority)
            onPageReady(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderPerf.log { "prefetch_page_failed key=${page.cacheKey} msg=${e.message}" }
            onPageFailed(page)
        }
    }

    /** Visible page first, then the pages ahead (normal), then the few behind (lowest). */
    private fun window(index: Int): List<Pair<PageImage, PagePriority>> {
        val window = LinkedHashMap<PageImage, PagePriority>()
        window[pages[index]] = PagePriority.Visible
        for (offset in 1..ahead) {
            pages.getOrNull(index + offset)?.let { window.putIfAbsent(it, PagePriority.Nearby) }
        }
        for (offset in 1..behind) {
            pages.getOrNull(index - offset)?.let { window.putIfAbsent(it, PagePriority.Behind) }
        }
        return window.map { (page, priority) -> page to priority }
    }

    private companion object {
        const val PREFETCH_CONCURRENCY = 3
    }
}
