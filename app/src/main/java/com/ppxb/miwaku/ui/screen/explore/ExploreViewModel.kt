package com.ppxb.miwaku.ui.screen.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ExploreUiState {
    data object Loading : ExploreUiState

    data class Content(val comics: List<ComicSummary>) : ExploreUiState

    data class Failed(val message: String) : ExploreUiState
}

internal class ExploreViewModel : ViewModel() {
    private val _state = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = ExploreUiState.Loading
            _state.value =
                runCatching {
                    withContext(Dispatchers.IO) { AppGraph.comics.latest() }
                }.fold(
                    onSuccess = { ExploreUiState.Content(it) },
                    onFailure = { ExploreUiState.Failed("网络请求失败，请重试") },
                )
        }
    }
}
