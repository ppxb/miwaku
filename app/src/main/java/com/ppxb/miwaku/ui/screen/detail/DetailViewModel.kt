package com.ppxb.miwaku.ui.screen.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Content(val detail: ComicDetail) : DetailUiState

    data class Failed(val message: String) : DetailUiState
}

internal class DetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val id: String = checkNotNull(savedStateHandle.get<String>("id"))

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = DetailUiState.Loading
            try {
                val detail = withContext(Dispatchers.IO) { AppGraph.comics.detail(id) }
                AppGraph.history.record(
                    ComicSummary(
                        id = detail.id,
                        title = detail.title,
                        author = detail.author,
                        coverUrl = detail.coverUrl,
                    ),
                )
                _state.value = DetailUiState.Content(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Failed("加载失败，请重试")
            }
        }
    }
}
