package com.ppxb.miwaku.ui.reader.viewer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import com.ppxb.miwaku.ui.reader.chrome.ChapterTransitionItem
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One row of the continuous reader. Transitions and the footer live here rather than in the page
 * list, so a page index is always a page: pairing, progress and prefetch never see a non-page.
 */
internal sealed interface WebtoonItem {
    val pageIndex: Int

    val key: String

    data class Page(val page: ReaderPage, override val pageIndex: Int) : WebtoonItem {
        override val key: String get() = "page:${page.key}"
    }

    data class Transition(
        val from: String,
        val to: String,
        override val pageIndex: Int,
    ) : WebtoonItem {
        override val key: String get() = "transition:$from>$to"
    }

    data class Footer(override val pageIndex: Int) : WebtoonItem {
        override val key: String get() = "footer"
    }
}

/**
 * Continuous vertical reading. Unlike the paged viewer there are no screens, so a "page turn" is
 * just a scroll by one row; the shell still drives it through [Viewer] so keys and arrows behave
 * the same in both modes.
 */
@Stable
internal class WebtoonViewerState(
    val listState: LazyListState,
    val items: List<WebtoonItem>,
    private val totalPages: Int,
) : Viewer {
    override val pageCount: Int get() = totalPages

    override val currentPage: Int
        get() = items.getOrNull(listState.firstVisibleItemIndex)?.pageIndex ?: 0

    override val isFirstPage: Boolean get() = listState.firstVisibleItemIndex <= 0

    override val isLastPage: Boolean get() = listState.firstVisibleItemIndex >= items.lastIndex

    override suspend fun moveNext() {
        if (!isLastPage) listState.animateScrollToItem(listState.firstVisibleItemIndex + 1)
    }

    override suspend fun movePrevious() {
        if (!isFirstPage) listState.animateScrollToItem(listState.firstVisibleItemIndex - 1)
    }

    override suspend fun scrollToPage(page: Int) {
        val index = items.indexOfFirst { it is WebtoonItem.Page && it.pageIndex >= page }
        listState.scrollToItem(if (index >= 0) index else items.lastIndex.coerceAtLeast(0))
    }
}

@Composable
internal fun rememberWebtoonViewerState(
    pages: List<ReaderPage>,
    chapterTitles: Map<String, String>,
    hasFooter: Boolean,
    initialPage: Int,
): WebtoonViewerState {
    val items = remember(pages, chapterTitles, hasFooter) {
        buildWebtoonItems(pages, chapterTitles, hasFooter)
    }
    val initialIndex =
        remember(items, initialPage) {
            items.indexOfFirst { it is WebtoonItem.Page && it.pageIndex >= initialPage }
                .coerceAtLeast(0)
        }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    return remember(listState, items, pages.size) {
        WebtoonViewerState(listState, items, pages.size)
    }
}

@Composable
internal fun WebtoonViewer(
    state: WebtoonViewerState,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
    pageContent: @Composable (ReaderPage, Modifier) -> Unit,
) {
    LaunchedEffect(state) {
        snapshotFlow { state.listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageChanged(state.currentPage) }
    }

    LazyColumn(state = state.listState, modifier = modifier) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is WebtoonItem.Page -> pageContent(item.page, Modifier.fillMaxWidth())
                is WebtoonItem.Transition -> ChapterTransitionItem(item.from, item.to)
                is WebtoonItem.Footer -> footer()
            }
        }
    }
}

internal fun buildWebtoonItems(
    pages: List<ReaderPage>,
    chapterTitles: Map<String, String>,
    hasFooter: Boolean,
): List<WebtoonItem> {
    val items = ArrayList<WebtoonItem>(pages.size + 8)
    pages.forEachIndexed { index, page ->
        val previous = pages.getOrNull(index - 1)
        if (previous != null && previous.chapterId != page.chapterId) {
            items +=
                WebtoonItem.Transition(
                    from = chapterTitles[previous.chapterId].orEmpty(),
                    to = chapterTitles[page.chapterId].orEmpty(),
                    pageIndex = index,
                )
        }
        items += WebtoonItem.Page(page, index)
    }
    if (hasFooter && pages.isNotEmpty()) {
        items += WebtoonItem.Footer(pages.lastIndex)
    }
    return items
}
