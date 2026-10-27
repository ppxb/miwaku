package com.ppxb.miwaku.ui.screen.reader

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.image.ReaderPerf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Downloads the pages around the viewport, nearest first. The visible page is always the first
 * request, so this never delays the page the reader is looking at.
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
                for (page in window) {
                    if (!isActive) return@launch
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
                ReaderPerf.log { "prefetch_done index=$target ms=${ReaderPerf.mark() - started}" }
            }
    }

    fun cancel() {
        job?.cancel()
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
    }
}
