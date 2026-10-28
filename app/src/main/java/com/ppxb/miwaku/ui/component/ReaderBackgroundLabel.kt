package com.ppxb.miwaku.ui.component

import androidx.annotation.StringRes
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.settings.ReaderBackground

@StringRes
fun ReaderBackground.labelRes(): Int =
    when (this) {
        ReaderBackground.MatchTheme -> R.string.settings_reader_background_match_theme
        ReaderBackground.Black -> R.string.settings_reader_background_black
        ReaderBackground.Paper -> R.string.settings_reader_background_paper
    }
