package com.ppxb.miwaku.core.data.settings

/**
 * Background painted behind reader pages. Bars and page chrome keep following the app theme;
 * only the canvas and the per-page placeholder change.
 */
enum class ReaderBackground {
    MatchTheme,
    Black,
    Paper,
}

/** How chapter pages are laid out; paged modes show one page at a time. */
enum class ReadingMode {
    Webtoon,
    PagedLeftToRight,
    PagedRightToLeft,
    VerticalPager,
}
