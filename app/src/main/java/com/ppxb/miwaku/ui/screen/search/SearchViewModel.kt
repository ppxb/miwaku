package com.ppxb.miwaku.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.data.AppGraph
import com.ppxb.miwaku.data.model.ComicSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface SearchUiState {
    data object Idle : SearchUiState

    data object Loading : SearchUiState

    data class Content(val total: Int, val comics: List<ComicSummary>) : SearchUiState

    data class Failed(val message: String) : SearchUiState
}

internal class SearchViewModel : ViewModel() {
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return

        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _state.value = SearchUiState.Loading
                try {
                    val result = withContext(Dispatchers.IO) { AppGraph.comics.search(keyword) }
                    _state.value = SearchUiState.Content(result.total, result.comics)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = SearchUiState.Failed("网络请求失败，请重试")
                }
            }
    }

    fun reset() {
        searchJob?.cancel()
        searchJob = null
        _state.value = SearchUiState.Idle
    }
}
