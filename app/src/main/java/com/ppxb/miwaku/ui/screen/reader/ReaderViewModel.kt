package com.ppxb.miwaku.ui.screen.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ChapterSummary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

internal sealed interface NextChapterState {
    data object Unknown : NextChapterState

    data object None : NextChapterState

    data class Next(val chapter: ChapterSummary) : NextChapterState
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

    private val _nextChapter = MutableStateFlow<NextChapterState>(NextChapterState.Unknown)
    val nextChapter: StateFlow<NextChapterState> = _nextChapter.asStateFlow()

    private var loadJob: Job? = null
    private var prefetcher: ReaderPrefetcher? = null
    private var detailJob: Job? = null
    private var nextManifestJob: Job? = null
    private var nextPrefetchJob: Job? = null
    private var currentContent: ChapterContent? = null
    private var currentPage = initialPage
    private val nextPrefetchStarted = AtomicBoolean(false)

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        prefetcher?.cancel()
        prefetcher = null
        detailJob?.cancel()
        nextManifestJob?.cancel()
        nextPrefetchJob?.cancel()
        nextPrefetchStarted.set(false)
        currentContent = null
        currentPage = initialPage
        _nextChapter.value = NextChapterState.Unknown
        _failedPages.value = emptySet()
        ReaderPerf.log {
            "reader_load_start chapter=$chapterId page=$initialPage"
        }
        loadJob = viewModelScope.launch {
            _state.value = ReaderUiState.Loading
            val started = ReaderPerf.mark()
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, chapterId) }
                currentContent = content
                _state.value = ReaderUiState.Content(content)
                ReaderPerf.log {
                    "reader_content_ready chapter=$chapterId pages=${content.pages.size} ms=${ReaderPerf.mark() - started}"
                }
                startPrefetch(content)
                resolveNextChapter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderPerf.log { "reader_load_failed chapter=$chapterId msg=${e.message}" }
                android.util.Log.w("Reader", "chapter load failed: $albumId/$chapterId", e)
                _state.value = ReaderUiState.Failed("加载失败，请重试")
            }
        }
    }

    fun recordPage(page: Int) {
        val lastIndex = currentContent?.pages?.lastIndex ?: -1
        val safePage = if (lastIndex >= 0) page.coerceIn(0, lastIndex) else page
        currentPage = safePage
        prefetcher?.update(safePage)
        maybePrefetchNext(safePage)
        AppGraph.progress.record(albumId, chapterId, safePage)
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

    fun retryNextChapter() {
        if (_nextChapter.value is NextChapterState.Next || _nextChapter.value is NextChapterState.None) {
            return
        }
        resolveNextChapter()
    }

    override fun onCleared() {
        prefetcher?.cancel()
        detailJob?.cancel()
        nextManifestJob?.cancel()
        nextPrefetchJob?.cancel()
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
                ahead = AppGraph.settings.readerPrefetchAhead.value,
                prefetchAllowed = { AppGraph.isPrefetchAllowed() },
            ).also { it.start(initialPage) }
        viewModelScope.launch(Dispatchers.IO) { AppGraph.pageStore.loadCachedSizes(images) }
    }

    private fun resolveNextChapter() {
        detailJob?.cancel()
        detailJob = viewModelScope.launch(Dispatchers.IO) {
            val started = ReaderPerf.mark()
            val detail =
                try {
                    AppGraph.comics.detail(albumId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (detail == null) {
                ReaderPerf.log { "next_resolve_failed chapter=$chapterId" }
                return@launch
            }
            val index = detail.chapters.indexOfFirst { it.id == chapterId }
            if (index < 0) {
                ReaderPerf.log {
                    "next_resolve_missing chapter=$chapterId chapters=${detail.chapters.size}"
                }
                _nextChapter.value =
                    if (detail.chapters.size <= 1) {
                        NextChapterState.None
                    } else {
                        NextChapterState.Unknown
                    }
                return@launch
            }
            val next = detail.chapters.getOrNull(index + 1)
            _nextChapter.value = if (next != null) NextChapterState.Next(next) else NextChapterState.None
            ReaderPerf.log {
                "next_resolved chapter=$chapterId next=${next?.id ?: "none"} ms=${ReaderPerf.mark() - started}"
            }
            if (next != null) {
                prefetchNextManifest(next)
                maybePrefetchNext(currentPage)
            }
        }
    }

    private fun prefetchNextManifest(next: ChapterSummary) {
        nextManifestJob?.cancel()
        nextManifestJob =
            viewModelScope.launch(Dispatchers.IO) {
                if (!AppGraph.isPrefetchAllowed()) {
                    ReaderPerf.log { "next_manifest_skipped_metered next=${next.id}" }
                    return@launch
                }
                val started = ReaderPerf.mark()
                ReaderPerf.log { "next_manifest_start next=${next.id}" }
                try {
                    AppGraph.comics.chapter(albumId, next.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The transition will fetch it again if needed.
                }
                ReaderPerf.log {
                    "next_manifest_done next=${next.id} ms=${ReaderPerf.mark() - started}"
                }
            }
    }

    private fun maybePrefetchNext(page: Int) {
        val content = currentContent ?: return
        val next = (_nextChapter.value as? NextChapterState.Next)?.chapter ?: return
        if (content.pages.isEmpty()) return
        if (page < content.pages.size - NEXT_PREFETCH_THRESHOLD) return
        if (!AppGraph.isPrefetchAllowed()) return
        if (!nextPrefetchStarted.compareAndSet(false, true)) return
        nextPrefetchJob =
            viewModelScope.launch(Dispatchers.IO) {
                val started = ReaderPerf.mark()
                ReaderPerf.log { "next_prefetch_start next=${next.id} page=$page" }
                val nextContent =
                    try {
                        AppGraph.comics.chapter(albumId, next.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (nextContent != null) {
                    val images =
                        nextContent.pages.mapIndexed { index, pageItem ->
                            PageImage(
                                chapterId = nextContent.chapterId,
                                fileName = pageItem.fileName.ifBlank { "page-$index" },
                                index = index,
                                splitCount = pageItem.splitCount,
                                url = pageItem.url,
                            )
                        }
                    images.take(NEXT_PREFETCH_PAGES).forEach { image ->
                        try {
                            AppGraph.pageStore.prepare(image)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The reader will retry on demand after the transition.
                        }
                    }
                }
                ReaderPerf.log {
                    "next_prefetch_done next=${next.id} ms=${ReaderPerf.mark() - started}"
                }
            }
    }

    private companion object {
        const val NEXT_PREFETCH_THRESHOLD = 5
        const val NEXT_PREFETCH_PAGES = 3
    }
}
