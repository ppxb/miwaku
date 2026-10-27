package com.ppxb.miwaku.ui.screen.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.model.ChapterContent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    val pageSizes: StateFlow<Map<String, PageSize>> = AppGraph.pageStore.sizes

    private val _failedPages = MutableStateFlow<Set<String>>(emptySet())
    val failedPages: StateFlow<Set<String>> = _failedPages.asStateFlow()

    private var loadJob: Job? = null
    private var prefetcher: ReaderPrefetcher? = null
    private var currentPage = initialPage

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        prefetcher?.cancel()
        prefetcher = null
        currentPage = initialPage
        _failedPages.value = emptySet()
        loadJob = viewModelScope.launch {
            _state.value = ReaderUiState.Loading
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, chapterId) }
                _state.value = ReaderUiState.Content(content)
                startPrefetch(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Reader", "chapter load failed: $albumId/$chapterId", e)
                _state.value = ReaderUiState.Failed("加载失败，请重试")
            }
        }
    }

    fun recordPage(page: Int) {
        currentPage = page
        prefetcher?.update(page)
        AppGraph.progress.record(albumId, chapterId, page)
    }

    fun fileOf(image: PageImage): File? = AppGraph.pageStore.cachedFile(image)

    fun ensurePage(image: PageImage) {
        viewModelScope.launch {
            try {
                AppGraph.pageStore.prepare(image)
                _failedPages.update { it - image.cacheKey }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _failedPages.update { it + image.cacheKey }
            }
        }
    }

    fun retry(image: PageImage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppGraph.pageStore.invalidate(image) }
            _failedPages.update { it - image.cacheKey }
            ensurePage(image)
            prefetcher?.update(currentPage)
        }
    }

    override fun onCleared() {
        prefetcher?.cancel()
        super.onCleared()
    }

    private fun startPrefetch(content: ChapterContent) {
        val images =
            content.pages.mapIndexed { index, page ->
                PageImage(
                    chapterId = content.chapterId,
                    fileName = page.fileName.ifBlank { "page-$index" },
                    index = index,
                    splitCount = page.splitCount,
                    url = page.url,
                )
            }
        prefetcher =
            ReaderPrefetcher(
                scope = viewModelScope,
                store = AppGraph.pageStore,
                pages = images,
                onPageReady = { image -> _failedPages.update { it - image.cacheKey } },
                onPageFailed = { image -> _failedPages.update { it + image.cacheKey } },
            ).also { it.start(initialPage) }
        viewModelScope.launch(Dispatchers.IO) { AppGraph.pageStore.loadCachedSizes(images) }
    }
}
