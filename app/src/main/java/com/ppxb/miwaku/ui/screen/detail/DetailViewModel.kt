package com.ppxb.miwaku.ui.screen.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    val resume: StateFlow<ReadingProgress?> =
        AppGraph.progress.entries
            .map { it[id] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        prefetchJob?.cancel()
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
                prefetchFirstChapter(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DetailUiState.Failed("加载失败，请重试")
            }
        }
    }

    /**
     * Warm the reader before the user taps it: resume chapter + the saved page if there is
     * progress, otherwise the first chapter from page 0.
     */
    private fun prefetchFirstChapter(detail: ComicDetail) {
        prefetchJob =
            viewModelScope.launch(Dispatchers.IO) {
                val progress = AppGraph.progress.entries.value[detail.id]
                val resumeChapter =
                    progress?.let { saved ->
                        detail.chapters.firstOrNull { it.id == saved.chapterId }
                    }
                val target = resumeChapter ?: detail.chapters.firstOrNull() ?: return@launch
                val startPage =
                    if (progress != null && progress.chapterId == target.id) {
                        progress.page
                    } else {
                        0
                    }
                val started = ReaderPerf.mark()
                ReaderPerf.log {
                    "detail_prefetch_start chapter=${target.id} page=$startPage"
                }
                val content =
                    try {
                        AppGraph.comics.chapter(detail.id, target.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                if (content.pages.isEmpty()) return@launch
                val safePage = startPage.coerceIn(0, content.pages.lastIndex)
                val from = (safePage - 1).coerceAtLeast(0)
                val to = (safePage + PREFETCH_AHEAD).coerceAtMost(content.pages.lastIndex)
                for (index in from..to) {
                    val page = content.pages[index]
                    val image =
                        PageImage(
                            chapterId = content.chapterId,
                            fileName = page.fileName.ifBlank { "page-$index" },
                            index = index,
                            splitCount = page.splitCount,
                            url = page.url,
                        )
                    try {
                        AppGraph.pageStore.prepare(image)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The reader will fetch it on demand.
                    }
                }
                ReaderPerf.log {
                    "detail_prefetch_done chapter=${target.id} from=$from to=$to ms=${ReaderPerf.mark() - started}"
                }
            }
    }

    private companion object {
        const val PREFETCH_AHEAD = 4
    }
}
