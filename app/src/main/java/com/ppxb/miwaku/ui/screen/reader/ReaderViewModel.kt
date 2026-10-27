package com.ppxb.miwaku.ui.screen.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ChapterContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Content(val content: ChapterContent) : ReaderUiState

    data class Failed(val message: String) : ReaderUiState
}

internal class ReaderViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val albumId: String = checkNotNull(savedStateHandle.get<String>("albumId"))
    private val chapterId: String = checkNotNull(savedStateHandle.get<String>("chapterId"))
    val initialPage: Int = savedStateHandle.get<Int>("page") ?: 0

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = ReaderUiState.Loading
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, chapterId) }
                _state.value = ReaderUiState.Content(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Reader", "chapter load failed: $albumId/$chapterId", e)
                _state.value = ReaderUiState.Failed("加载失败，请重试")
            }
        }
    }

    fun recordPage(page: Int) {
        AppGraph.progress.record(albumId, chapterId, page)
    }
}
