package com.ppxb.miwaku.ui.screen.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ExploreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ExploreUiState {
    data object Loading : ExploreUiState

    data class Content(
        val sections: List<ExploreSection>,
        val selected: Int = 0,
    ) : ExploreUiState

    data class Failed(val message: String) : ExploreUiState
}

internal class ExploreViewModel : ViewModel() {
    private val _state = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.value = ExploreUiState.Loading
        loadJob =
            viewModelScope.launch {
                _state.value =
                    runCatching {
                        withContext(Dispatchers.IO) { AppGraph.comics.exploreSections() }
                    }.fold(
                        onSuccess = { ExploreUiState.Content(it) },
                        onFailure = { ExploreUiState.Failed("网络请求失败，请重试") },
                    )
            }
    }

    fun select(index: Int) {
        val current = _state.value as? ExploreUiState.Content ?: return
        if (index !in current.sections.indices) return
        _state.value = current.copy(selected = index)
    }
}
