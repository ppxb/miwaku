package com.ppxb.miwaku.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PagePriority
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.core.data.settings.PageLayoutMode
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ReadingProgress
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import com.ppxb.miwaku.ui.reader.source.PageSource
import com.ppxb.miwaku.ui.reader.ReaderPrefetcher
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

    data class Content(
        val pages: List<ReaderPage>,
        /** Page the viewer must open on; resolved from the saved progress. */
        val startPage: Int,
    ) : ReaderUiState

    data class Failed(val message: String) : ReaderUiState
}

internal sealed interface NextChapterState {
    data object Unknown : NextChapterState

    data object None : NextChapterState

    data class Next(val chapter: ChapterSummary) : NextChapterState
}

/**
 * Owns the page window, progress and prefetch. Deliberately thin compared to komikku's: there is one
 * source, so the window is a flat list of pages and appending the next chapter is just a concat.
 * Everything the viewers need is exposed as plain state; they never touch the repository.
 */
internal class ReaderViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val albumId: String = checkNotNull(savedState.get<String>("albumId"))

    private var chapterId: String = checkNotNull(savedState.get<String>("chapterId"))
    private var startPage: Int = savedState.get<Int>("page") ?: 0

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterSummary>>(emptyList())
    val chapters: StateFlow<List<ChapterSummary>> = _chapters.asStateFlow()

    private val _albumTitle = MutableStateFlow("")
    val albumTitle: StateFlow<String> = _albumTitle.asStateFlow()

    private val _mode = MutableStateFlow(currentMode())
    val mode: StateFlow<ReadingMode> = _mode.asStateFlow()

    private val _layout = MutableStateFlow(AppGraph.settings.pageLayout.value)
    val layout: StateFlow<PageLayoutMode> = _layout.asStateFlow()

    private val _nextChapter = MutableStateFlow<NextChapterState>(NextChapterState.Unknown)
    val nextChapter: StateFlow<NextChapterState> = _nextChapter.asStateFlow()

    private val _previousChapter = MutableStateFlow<ChapterSummary?>(null)
    val previousChapter: StateFlow<ChapterSummary?> = _previousChapter.asStateFlow()

    /** Page dimensions, owned by the cache; the viewers read them per page. */
    val sizes: StateFlow<Map<String, PageSize>> = AppGraph.pageStore.sizes

    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var detailJob: Job? = null
    private var prefetcher: ReaderPrefetcher? = null

    /** Index into the current window, kept so neighbours resolve without reading saved state. */
    private var currentGlobal = 0

    /** One progress session per loaded chapter; the store drops positions from stale sessions. */
    private var session = 0L

    init {
        load()
    }

    /** Snapshot used to put the previous window back when a jump fails. */
    private data class Rollback(
        val content: ReaderUiState.Content,
        val chapterId: String,
        val startPage: Int,
        val global: Int,
    )

    private var rollback: Rollback? = null

    fun load(keepContent: Boolean = false) {
        session = AppGraph.progress.beginSession()
        loadJob?.cancel()
        appendJob?.cancel()
        detailJob?.cancel()
        prefetcher?.cancel()
        prefetcher = null
        if (!keepContent) {
            _state.value = ReaderUiState.Loading
            _nextChapter.value = NextChapterState.Unknown
            _previousChapter.value = null
        }

        loadJob =
            viewModelScope.launch {
                val started = ReaderPerf.mark()
                val pages =
                    try {
                        fetchPages(chapterId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ReaderPerf.log { "reader_load_failed chapter=$chapterId msg=${e.message}" }
                        restoreOrFail(keepContent, "加载失败，请重试")
                        return@launch
                    }
                if (pages.isEmpty()) {
                    restoreOrFail(keepContent, "本章没有图片")
                    return@launch
                }
                val start =
                    when {
                        // The detail screen asks to resume with -2; -1 means "start at the end".
                        startPage == RESUME_SAVED -> resumeStart(pages)
                        startPage < 0 -> pages.lastIndex
                        else -> startPage.coerceIn(0, pages.lastIndex)
                    }
                rollback = null
                _state.value = ReaderUiState.Content(pages, start)
                currentGlobal = start
                savedState["page"] = start
                ReaderPerf.log {
                    "reader_content_ready chapter=$chapterId pages=${pages.size} ms=${ReaderPerf.mark() - started}"
                }
                startPrefetch(pages, start)
                viewModelScope.launch(Dispatchers.IO) {
                    AppGraph.pageStore.loadCachedSizes(pages.map { it.image })
                }
                resolveChapters()
            }
    }

    /**
     * Where "continue reading" resumes: the saved page of this chapter, matched by file name so a
     * chapter whose images were re-encoded still lands on the same page.
     */
    private suspend fun resumeStart(pages: List<ReaderPage>): Int {
        val saved = runCatching { AppGraph.progress.chapter(chapterId) }.getOrNull() ?: return 0
        val byName = saved.fileName?.let { name -> pages.indexOfFirst { it.image.fileName == name } } ?: -1
        return (if (byName >= 0) byName else saved.page).coerceIn(0, pages.lastIndex)
    }

    /** Explicit jump from the chapter list or the previous/next arrows. */
    fun openChapter(
        target: String,
        startAtEnd: Boolean = false,
    ) {
        if (target == chapterId && _state.value is ReaderUiState.Content) return
        (_state.value as? ReaderUiState.Content)?.let { content ->
            rollback =
                Rollback(
                    content = content,
                    chapterId = chapterId,
                    startPage = content.pages.getOrNull(currentGlobal)?.image?.index ?: startPage,
                    global = currentGlobal,
                )
        }
        chapterId = target
        startPage = if (startAtEnd) -1 else 0
        savedState["chapterId"] = target
        load(keepContent = true)
    }

    /**
     * A failed jump must not cost the reader its place: put the previous window back and stay on it.
     * A failed first load has nothing to fall back to, so it reports the error instead.
     */
    private fun restoreOrFail(
        keepContent: Boolean,
        message: String,
    ) {
        val snapshot = rollback.takeIf { keepContent }
        if (snapshot == null) {
            _state.value = ReaderUiState.Failed(message)
            return
        }
        rollback = null
        chapterId = snapshot.chapterId
        startPage = snapshot.startPage
        currentGlobal = snapshot.global
        savedState["chapterId"] = snapshot.chapterId
        savedState["page"] = snapshot.startPage
        _state.value = snapshot.content
        ReaderPerf.log { "reader_jump_rolled_back chapter=${snapshot.chapterId} page=${snapshot.startPage}" }
        startPrefetch(snapshot.content.pages, snapshot.global)
        resolveChapters()
    }

    fun openNextChapter() {
        val next = (_nextChapter.value as? NextChapterState.Next)?.chapter ?: return
        openChapter(next.id)
    }

    fun openPreviousChapter() {
        val previous = _previousChapter.value ?: return
        openChapter(previous.id, startAtEnd = true)
    }

    /** Called by the viewer whenever the visible page changes. */
    fun onPageVisible(global: Int) {
        val content = _state.value as? ReaderUiState.Content ?: return
        val page = content.pages.getOrNull(global) ?: return
        currentGlobal = global
        if (page.chapterId != chapterId) chapterId = page.chapterId
        savedState["chapterId"] = page.chapterId
        savedState["page"] = page.image.index
        AppGraph.progress.record(
            session,
            albumId,
            ReadingProgress(page.chapterId, page.image.index, page.image.fileName),
        )
        prefetcher?.update(global)
        updateNeighbors()
        if (global >= content.pages.size - APPEND_THRESHOLD) appendNext()
    }

    /** Resolves a page to something Coil can read, downloading it first when needed. */
    suspend fun loadPage(
        page: ReaderPage,
        force: Boolean,
    ): PageSource? {
        if (force) withContext(Dispatchers.IO) { AppGraph.pageStore.invalidate(page.image) }
        val priority = if (force) PagePriority.Retry else PagePriority.Visible
        val file =
            try {
                AppGraph.pageStore.prepare(page.image, priority)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderPerf.log { "page_failed key=${page.key} msg=${e.message}" }
                return null
            }
        return PageSource.Cached(file)
    }

    fun setMode(mode: ReadingMode?) {
        AppGraph.readerModes.setMode(albumId, mode)
        _mode.value = mode ?: AppGraph.settings.defaultReadingMode.value
    }

    fun setLayout(mode: PageLayoutMode) {
        _layout.value = mode
        AppGraph.settings.setPageLayout(mode)
    }

    fun retryNextChapter() {
        when (_nextChapter.value) {
            is NextChapterState.Next -> appendNext()
            else -> resolveChapters()
        }
    }

    override fun onCleared() {
        AppGraph.progress.flushSession(session, end = true)
        prefetcher?.cancel()
        detailJob?.cancel()
        appendJob?.cancel()
        super.onCleared()
    }

    private suspend fun fetchPages(id: String): List<ReaderPage> =
        withContext(Dispatchers.IO) {
            val content = AppGraph.comics.chapter(albumId, id)
            content.pages.mapIndexed { index, payload ->
                ReaderPage(
                    image =
                        PageImage(
                            chapterId = content.chapterId,
                            fileName = payload.fileName.ifBlank { "page-$index" },
                            index = index,
                            splitCount = payload.splitCount,
                            url = payload.url,
                        ),
                )
            }
        }

    private fun startPrefetch(
        pages: List<ReaderPage>,
        start: Int,
    ) {
        prefetcher?.cancel()
        val lowMemory = AppGraph.settings.lowMemory.value
        prefetcher =
            ReaderPrefetcher(
                scope = viewModelScope,
                store = AppGraph.pageStore,
                pages = pages.map { it.image },
                ahead = if (lowMemory) LOW_MEMORY_PREFETCH_AHEAD else AppGraph.settings.readerPrefetchAhead.value,
                behind = if (lowMemory) LOW_MEMORY_PREFETCH_BEHIND else DEFAULT_PREFETCH_BEHIND,
                prefetchAllowed = { AppGraph.isPrefetchAllowed() },
            ).also { it.start(start) }
    }

    private fun resolveChapters() {
        detailJob?.cancel()
        detailJob =
            viewModelScope.launch(Dispatchers.IO) {
                val detail =
                    try {
                        AppGraph.comics.detail(albumId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                _chapters.value = detail.chapters
                _albumTitle.value = detail.title
                updateNeighbors()
                if (shouldAppendNext()) appendNext()
            }
    }

    /** Appends the chapter after the last one already in the window. */
    private fun appendNext() {
        val content = _state.value as? ReaderUiState.Content ?: return
        val lastChapterId = content.pages.lastOrNull()?.chapterId ?: return
        val list = _chapters.value
        val index = list.indexOfFirst { it.id == lastChapterId }
        if (index < 0) return
        val next = list.getOrNull(index + 1) ?: return
        if (content.pages.any { it.chapterId == next.id }) return
        if (appendJob?.isActive == true) return
        appendJob =
            viewModelScope.launch {
                val added =
                    try {
                        fetchPages(next.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                if (added.isEmpty()) return@launch
                _state.update { current ->
                    if (current is ReaderUiState.Content) {
                        current.copy(pages = current.pages + added)
                    } else {
                        current
                    }
                }
                ReaderPerf.log { "append_done next=${next.id} pages=${added.size}" }
                updateNeighbors()
            }
    }

    private fun shouldAppendNext(): Boolean {
        val content = _state.value as? ReaderUiState.Content ?: return false
        val lastChapterId = content.pages.lastOrNull()?.chapterId ?: return false
        return _chapters.value.indexOfFirst { it.id == lastChapterId } >= 0
    }

    private fun updateNeighbors() {
        val content = _state.value as? ReaderUiState.Content ?: return
        val list = _chapters.value
        if (list.isEmpty()) return
        val activeId = content.pages.getOrNull(currentGlobal)?.chapterId ?: chapterId
        val position = list.indexOfFirst { it.id == activeId }
        _previousChapter.value = if (position >= 0) list.getOrNull(position - 1) else null
        _nextChapter.value =
            list.getOrNull(position + 1)
                ?.let(NextChapterState::Next)
                ?: NextChapterState.None
    }

    private fun currentMode(): ReadingMode =
        AppGraph.readerModes.modes.value[albumId] ?: AppGraph.settings.defaultReadingMode.value

    private companion object {
        /** Page value the detail screen passes when the reader must resume saved progress. */
        const val RESUME_SAVED = -2
        const val APPEND_THRESHOLD = 5
        const val DEFAULT_PREFETCH_BEHIND = 2
        const val LOW_MEMORY_PREFETCH_AHEAD = 2
        const val LOW_MEMORY_PREFETCH_BEHIND = 1
    }
}
