package com.ppxb.miwaku.ui.reader.viewer

/**
 * Navigation contract shared by the reader shell (tap zones, volume keys, chapter arrows) and a
 * concrete viewer. Mirrors komikku's `Viewer`: a reading mode is a [Viewer] implementation, not a
 * branch in the screen, so adding one never touches loading, progress or prefetch.
 */
interface Viewer {
    val pageCount: Int

    /** Index of the page the reader is on; always a real page, never a chapter transition. */
    val currentPage: Int

    val isLastPage: Boolean
    val isFirstPage: Boolean

    suspend fun moveNext()

    suspend fun movePrevious()

    /** Jumps straight to [page], used by the scrubber. */
    suspend fun scrollToPage(page: Int)
}
