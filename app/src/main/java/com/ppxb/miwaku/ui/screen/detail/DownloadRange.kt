package com.ppxb.miwaku.ui.screen.detail

/** Download scopes offered by the detail-page dropdown. */
internal enum class DownloadRange(val nextCount: Int? = null) {
    All,
    Next1(1),
    Next5(5),
    Next10(10),
    Next25(25),
    Unread,
}
