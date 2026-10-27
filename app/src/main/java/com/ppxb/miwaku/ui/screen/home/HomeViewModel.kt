package com.ppxb.miwaku.ui.screen.home

import androidx.lifecycle.ViewModel
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.flow.StateFlow

internal class HomeViewModel : ViewModel() {
    val history: StateFlow<List<ComicSummary>> = AppGraph.history.entries
}
