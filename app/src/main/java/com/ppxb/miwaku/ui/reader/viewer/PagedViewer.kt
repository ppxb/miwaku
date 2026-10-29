package com.ppxb.miwaku.ui.reader.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import com.ppxb.miwaku.ui.reader.chrome.ChapterTransitionItem
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One screen of the paged reader. Transitions are screens too, so the chapter break can be swiped
 * through, but they never enter the page list: pairing, progress and prefetch only ever see pages.
 */
internal sealed interface PagedSlot {
    data class Pages(val pages: List<ReaderPage>) : PagedSlot

    data class Transition(val from: String, val to: String) : PagedSlot
}

/**
 * Paged reading, one screen at a time. A screen holds one page, or two when [PageLayout.Double] and
 * the pages allow it — the pairing itself lives in [PageLayout], so this class only maps screens
 * back to page indices.
 *
 * State is hoisted (komikku keeps the same split between `PagerViewer` and its pager) so the shell
 * can drive page turns from tap zones or keys without knowing the layout.
 */
@Stable
internal class PagedViewerState(
    val pagerState: PagerState,
    val slots: List<PagedSlot>,
    private val slotStarts: List<Int>,
    private val totalPages: Int,
    private val hasFooter: Boolean,
) : Viewer {
    override val pageCount: Int get() = totalPages

    override val currentPage: Int
        get() = slotStarts.getOrElse(pagerState.currentPage.coerceAtMost(slots.lastIndex)) { 0 }

    override val isFirstPage: Boolean get() = pagerState.currentPage <= 0

    override val isLastPage: Boolean get() = pagerState.currentPage >= slotCount - 1

    /** Screens plus the optional end-of-window footer. */
    private val slotCount: Int get() = slots.size + if (hasFooter) 1 else 0

    override suspend fun moveNext() {
        if (!isLastPage) pagerState.animateScrollToPage(pagerState.currentPage + 1)
    }

    override suspend fun movePrevious() {
        if (!isFirstPage) pagerState.animateScrollToPage(pagerState.currentPage - 1)
    }

    /** Scrolls to the screen holding [page], used when restoring progress. */
    override suspend fun scrollToPage(page: Int) {
        val slot = slotStarts.indexOfLast { it <= page }.coerceAtLeast(0)
        pagerState.scrollToPage(slot)
    }
}

@Composable
internal fun rememberPagedViewerState(
    pages: List<ReaderPage>,
    layout: PageLayout,
    chapterTitles: Map<String, String>,
    initialPage: Int,
    hasFooter: Boolean = false,
): PagedViewerState {
    val slots = remember(pages, layout, chapterTitles) {
        buildPagedSlots(pages, layout, chapterTitles)
    }
    val slotStarts = remember(slots) { pagedSlotStarts(slots) }
    val initialSlot = remember(slotStarts, initialPage) {
        slotStarts.indexOfLast { it <= initialPage }.coerceAtLeast(0)
    }
    val pagerState =
        rememberPagerState(initialPage = initialSlot) {
            slots.size + if (hasFooter) 1 else 0
        }
    return remember(pagerState, slots, slotStarts, pages.size, hasFooter) {
        PagedViewerState(pagerState, slots, slotStarts, pages.size, hasFooter)
    }
}

/**
 * Renders the current screen. [pageContent] receives the page and the box it must fill, so a double
 * screen simply calls it twice inside a row.
 */
@Composable
internal fun PagedViewer(
    state: PagedViewerState,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    reverse: Boolean = false,
    footer: @Composable () -> Unit = {},
    pageContent: @Composable (ReaderPage, Modifier) -> Unit,
) {
    LaunchedEffect(state) {
        snapshotFlow { state.pagerState.currentPage }
            .distinctUntilChanged()
            .collect { onPageChanged(state.currentPage) }
    }

    if (vertical) {
        VerticalPager(state = state.pagerState, modifier = modifier) { index ->
            Slot(state, index, footer, pageContent)
        }
    } else {
        HorizontalPager(state = state.pagerState, modifier = modifier, reverseLayout = reverse) { index ->
            Slot(state, index, footer, pageContent)
        }
    }
}

@Composable
private fun Slot(
    state: PagedViewerState,
    index: Int,
    footer: @Composable () -> Unit,
    pageContent: @Composable (ReaderPage, Modifier) -> Unit,
) {
    when (val slot = state.slots.getOrNull(index)) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { footer() }

        is PagedSlot.Transition ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ChapterTransitionItem(slot.from, slot.to)
            }

        is PagedSlot.Pages ->
            if (slot.pages.size == 1) {
                pageContent(slot.pages[0], Modifier.fillMaxSize())
            } else {
                Row(Modifier.fillMaxSize()) {
                    pageContent(slot.pages[0], Modifier.weight(1f).fillMaxHeight())
                    pageContent(slot.pages[1], Modifier.weight(1f).fillMaxHeight())
                }
            }
    }
}

/**
 * Page screens from [PageLayout], with a transition screen wherever the chapter changes. A
 * transition reports the page it precedes so the counter never sits on a divider.
 */
internal fun buildPagedSlots(
    pages: List<ReaderPage>,
    layout: PageLayout,
    chapterTitles: Map<String, String>,
): List<PagedSlot> {
    val pageSlots = PageLayout.slots(pages, layout)
    val result = ArrayList<PagedSlot>(pageSlots.size + 4)
    pageSlots.forEachIndexed { index, slot ->
        val previous = pageSlots.getOrNull(index - 1)
        if (previous != null && previous.last().chapterId != slot.first().chapterId) {
            result +=
                PagedSlot.Transition(
                    from = chapterTitles[previous.last().chapterId].orEmpty(),
                    to = chapterTitles[slot.first().chapterId].orEmpty(),
                )
        }
        result += PagedSlot.Pages(slot)
    }
    return result
}

/** First page index each screen represents; a transition shares it with the screen that follows. */
internal fun pagedSlotStarts(slots: List<PagedSlot>): List<Int> {
    var index = 0
    return slots.map { slot ->
        index.also { if (slot is PagedSlot.Pages) index += slot.pages.size }
    }
}
