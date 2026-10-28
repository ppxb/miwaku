package com.ppxb.miwaku.ui.component

import androidx.annotation.StringRes
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.settings.ReadingMode

@StringRes
fun ReadingMode.labelRes(): Int =
    when (this) {
        ReadingMode.Webtoon -> R.string.reader_mode_webtoon
        ReadingMode.PagedLeftToRight -> R.string.reader_mode_ltr
        ReadingMode.PagedRightToLeft -> R.string.reader_mode_rtl
        ReadingMode.VerticalPager -> R.string.reader_mode_vertical
    }
