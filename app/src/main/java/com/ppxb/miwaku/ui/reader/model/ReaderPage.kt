package com.ppxb.miwaku.ui.reader.model

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize

/** Load state of one page, mirroring komikku's `Page.State`. */
sealed interface PageState {
    data object Queued : PageState

    data object Loading : PageState

    data object Ready : PageState

    data class Failed(val message: String? = null) : PageState
}

/**
 * One page of a chapter. The cache-facing identity stays in [image] so the reader never builds a
 * second copy of chapter/file/index; only display state lives here.
 */
data class ReaderPage(
    val image: PageImage,
    val state: PageState = PageState.Queued,
    val size: PageSize? = null,
) {
    val key: String get() = image.cacheKey

    val chapterId: String get() = image.chapterId

    val splitCount: Int get() = image.splitCount

    /**
     * A page wider than it is tall is a scanned spread: it must occupy a whole screen by itself,
     * otherwise the double-page layout would cut it in half.
     */
    val isSpread: Boolean
        get() = size?.let { it.width > it.height } == true

    fun withState(newState: PageState): ReaderPage = copy(state = newState)

    fun withSize(newSize: PageSize?): ReaderPage = copy(size = newSize)
}

/** A chapter as the reader sees it: a flat page list plus what the header needs. */
data class ReaderChapter(
    val id: String,
    val albumId: String,
    val title: String,
    val pages: List<ReaderPage>,
) {
    val isEmpty: Boolean get() = pages.isEmpty()
}
