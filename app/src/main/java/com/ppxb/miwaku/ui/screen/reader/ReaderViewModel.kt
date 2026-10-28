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

/**
 * Consecutive chapters rendered as one continuous page list, mirroring the reference reader. Pages
 * of the next chapter are appended before the user reaches the end, so turning past the last page
 * simply continues into the next chapter.
 */
internal data class ReaderWindow(
    val chapters: List<ChapterContent>,
    val activeIndex: Int,
    val id: Int,
) {
    val active: ChapterContent get() = chapters[activeIndex]

    val totalPages: Int get() = chapters.sumOf { it.pages.size }

    fun startOf(index: Int): Int {
        var start = 0
        for (i in 0 until index) start += chapters[i].pages.size
        return start
    }

    /** Maps a global page index to (chapter index, page index inside that chapter). */
    fun locate(global: Int): Pair<Int, Int> {
        var start = 0
        for ((index, chapter) in chapters.withIndex()) {
            val size = chapter.pages.size
            if (global < start + size) return index to (global - start)
            start += size
        }
        val last = chapters.lastIndex.coerceAtLeast(0)
        return last to chapters[last].pages.lastIndex.coerceAtLeast(0)
    }
}

internal sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Content(val window: ReaderWindow) : ReaderUiState

    data class Failed(val message: String) : ReaderUiState
}

internal sealed interface NextChapterState {
    data object Unknown : NextChapterState

    data object None : NextChapterState

    data class Next(val chapter: ChapterSummary) : NextChapterState
}

internal class ReaderViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val albumId: String = checkNotNull(savedStateHandle.get<String>("albumId"))

    /**
     * Active chapter. It changes when the page list crosses into an appended chapter; written on the
     * main thread and read from IO while resolving the chapter list.
     */
    @Volatile
    var chapterId: String = checkNotNull(savedStateHandle.get<String>("chapterId"))
        private set

    /**
     * Page the first chapter should start at. `-1` means the last page, used when going back a
     * chapter so it opens at the end.
     */
    var startPage: Int = savedStateHandle.get<Int>("page") ?: 0
        private set

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterSummary>>(emptyList())
    val chapters: StateFlow<List<ChapterSummary>> = _chapters.asStateFlow()

    /**
     * Album title from the detail endpoint. The `/chapter` payload carries its own `name`, but the
     * source fills that per chapter (short name plus volume number), so the header prefers this.
     */
    private val _albumTitle = MutableStateFlow("")
    val albumTitle: StateFlow<String> = _albumTitle.asStateFlow()

    val pageSizes: StateFlow<Map<String, PageSize>> = AppGraph.pageStore.sizes

    private val _failedPages = MutableStateFlow<Set<String>>(emptySet())
    val failedPages: StateFlow<Set<String>> = _failedPages.asStateFlow()

    private val _nextChapter = MutableStateFlow<NextChapterState>(NextChapterState.Unknown)
    val nextChapter: StateFlow<NextChapterState> = _nextChapter.asStateFlow()

    private val _previousChapter = MutableStateFlow<ChapterSummary?>(null)
    val previousChapter: StateFlow<ChapterSummary?> = _previousChapter.asStateFlow()

    private var loadJob: Job? = null
    private var prefetcher: ReaderPrefetcher? = null
    private var detailJob: Job? = null
    private var nextManifestJob: Job? = null
    private var nextPrefetchJob: Job? = null
    private var appendJob: Job? = null
    private var windowId = 0
    private var currentGlobal = 0
    private var currentLocal = startPage
    private val nextPrefetchStarted = AtomicBoolean(false)
    private var prefetchedNextId: String? = null
    private var pendingTargetChapter: String? = null

    /** Snapshot used to restore the previous window when a chapter load fails. */
    private data class Rollback(
        val window: ReaderWindow,
        val chapterId: String,
        val startPage: Int,
        val global: Int,
    )

    private var rollback: Rollback? = null

    init {
        load()
    }

    /**
     * Replaces the window with [target]. Used by explicit jumps (chapter list, chapter arrows) and
     * by the previous/next buttons; natural page turns never go through here.
     */
    fun openChapterInPlace(target: String, startAtEnd: Boolean = false) {
        val content = _state.value as? ReaderUiState.Content
        if (target == chapterId && content != null) return
        if (content != null) {
            rollback = Rollback(content.window, chapterId, startPage, currentGlobal)
        }
        chapterId = target
        startPage = if (startAtEnd) -1 else 0
        pendingTargetChapter = target
        savedStateHandle["chapterId"] = target
        savedStateHandle["page"] = startPage
        load(keepContent = true)
    }

    fun load(keepContent: Boolean = false) {
        loadJob?.cancel()
        appendJob?.cancel()
        prefetcher?.cancel()
        prefetcher = null
        detailJob?.cancel()
        nextManifestJob?.cancel()
        nextPrefetchJob?.cancel()
        nextPrefetchStarted.set(false)
        currentGlobal = 0
        currentLocal = startPage
        _nextChapter.value = NextChapterState.Unknown
        _previousChapter.value = null
        _failedPages.value = emptySet()
        ReaderPerf.log {
            "reader_load_start chapter=$chapterId page=$startPage"
        }
        loadJob = viewModelScope.launch {
            if (!keepContent) _state.value = ReaderUiState.Loading
            val started = ReaderPerf.mark()
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, chapterId) }
                val resolvedStart =
                    if (startPage < 0) {
                        content.pages.lastIndex.coerceAtLeast(0)
                    } else {
                        startPage.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                    }
                currentGlobal = resolvedStart
                currentLocal = resolvedStart
                startPage = resolvedStart
                savedStateHandle["page"] = resolvedStart
                _state.value =
                    ReaderUiState.Content(
                        ReaderWindow(listOf(content), activeIndex = 0, id = ++windowId),
                    )
                ReaderPerf.log {
                    "reader_content_ready chapter=$chapterId pages=${content.pages.size} ms=${ReaderPerf.mark() - started}"
                }
                startPrefetch(content, resolvedStart)
                rollback = null
                pendingTargetChapter = null
                resolveChapters()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderPerf.log { "reader_load_failed chapter=$chapterId msg=${e.message}" }
                android.util.Log.w("Reader", "chapter load failed: $albumId/$chapterId", e)
                val restore = rollback
                if (keepContent && restore != null) {
                    // The jump failed: put the previous window back so the reader stays usable.
                    rollback = null
                    pendingTargetChapter = null
                    chapterId = restore.chapterId
                    startPage = restore.startPage
                    currentGlobal = restore.global
                    val window = restore.window
                    val (_, local) = window.locate(restore.global)
                    currentLocal = local
                    savedStateHandle["chapterId"] = restore.chapterId
                    savedStateHandle["page"] = local
                    _state.value = ReaderUiState.Content(window)
                    startPrefetch(window.active, local)
                    resolveChapters()
                } else {
                    pendingTargetChapter = null
                    _state.value = ReaderUiState.Failed("加载失败，请重试")
                }
            }
        }
    }

    /**
     * Called whenever the visible page changes. Updates the active chapter, progress and appends the
     * next chapter when the end of the window is near.
     */
    fun onPageVisible(windowId: Int, global: Int) {
        val content = _state.value as? ReaderUiState.Content ?: return
        if (content.window.id != windowId) return
        // A jump is loading; ignore stale reports from the window that is being replaced.
        if (pendingTargetChapter != null) return
        val window = content.window
        if (global !in 0 until window.totalPages) return
        val (chapterIndex, local) = window.locate(global)
        val chapter = window.chapters[chapterIndex]
        currentGlobal = global
        currentLocal = local
        if (chapterIndex != window.activeIndex) {
            chapterId = chapter.chapterId
            savedStateHandle["chapterId"] = chapter.chapterId
            _state.value = ReaderUiState.Content(window.copy(activeIndex = chapterIndex))
            startPrefetch(chapter, local)
            updateNeighbors()
        }
        savedStateHandle["page"] = local
        prefetcher?.update(local)
        maybePrefetchNext(local)
        AppGraph.progress.record(albumId, chapter.chapterId, local)
        if (global >= window.totalPages - APPEND_THRESHOLD) ensureNextLoaded()
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
            prefetcher?.update(currentLocal)
        }
    }

    fun retryNextChapter() {
        when (_nextChapter.value) {
            is NextChapterState.Next -> ensureNextLoaded()
            else -> resolveChapters()
        }
    }

    override fun onCleared() {
        prefetcher?.cancel()
        detailJob?.cancel()
        nextManifestJob?.cancel()
        nextPrefetchJob?.cancel()
        appendJob?.cancel()
        super.onCleared()
    }

    /** Loads the next chapter and appends its pages to the current window. */
    private fun ensureNextLoaded() {
        val content = _state.value as? ReaderUiState.Content ?: return
        val next = (_nextChapter.value as? NextChapterState.Next)?.chapter ?: return
        val window = content.window
        if (window.chapters.any { it.chapterId == next.id }) return
        if (appendJob?.isActive == true) return
        val windowIdAtStart = window.id
        appendJob =
            viewModelScope.launch(Dispatchers.IO) {
                val started = ReaderPerf.mark()
                ReaderPerf.log { "append_start next=${next.id}" }
                val loaded =
                    try {
                        AppGraph.comics.chapter(albumId, next.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ReaderPerf.log { "append_failed next=${next.id}" }
                        return@launch
                    }
                val current = _state.value as? ReaderUiState.Content ?: return@launch
                val currentWindow = current.window
                // The user may have jumped to another chapter while the manifest was loading.
                if (currentWindow.id != windowIdAtStart) return@launch
                if (currentWindow.chapters.any { it.chapterId == next.id }) return@launch
                if (loaded.pages.isEmpty()) {
                    ReaderPerf.log { "append_empty next=${next.id}" }
                    return@launch
                }
                ReaderPerf.log {
                    "append_done next=${next.id} pages=${loaded.pages.size} ms=${ReaderPerf.mark() - started}"
                }
                withContext(Dispatchers.Main) {
                    val latest = _state.value as? ReaderUiState.Content ?: return@withContext
                    if (latest.window.id != windowIdAtStart) return@withContext
                    if (latest.window.chapters.any { it.chapterId == next.id }) return@withContext
                    _state.value =
                        ReaderUiState.Content(
                            latest.window.copy(chapters = latest.window.chapters + loaded),
                        )
                    updateNeighbors()
                }
                viewModelScope.launch(Dispatchers.IO) {
                    AppGraph.pageStore.loadCachedSizes(
                        loaded.pages.mapIndexed { index, page ->
                            PageImage(
                                chapterId = loaded.chapterId,
                                fileName = page.fileName.ifBlank { "page-$index" },
                                index = index,
                                splitCount = page.splitCount,
                                url = page.url,
                            )
                        },
                    )
                }
            }
    }

    private fun startPrefetch(content: ChapterContent, startPage: Int) {
        prefetcher?.cancel()
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
                ahead =
                    if (AppGraph.settings.lowMemory.value) {
                        minOf(AppGraph.settings.readerPrefetchAhead.value, LOW_MEMORY_PREFETCH_AHEAD)
                    } else {
                        AppGraph.settings.readerPrefetchAhead.value
                    },
                behind = if (AppGraph.settings.lowMemory.value) LOW_MEMORY_PREFETCH_BEHIND else 2,
                prefetchAllowed = { AppGraph.isPrefetchAllowed() },
            ).also { it.start(startPage) }
        viewModelScope.launch(Dispatchers.IO) { AppGraph.pageStore.loadCachedSizes(images) }
    }

    private fun resolveChapters() {
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
            _chapters.value = detail.chapters
            _albumTitle.value = detail.title
            updateNeighbors()
            ReaderPerf.log {
                "next_resolved chapter=$chapterId ms=${ReaderPerf.mark() - started}"
            }
        }
    }

    /** Recomputes the previous/next chapter relative to the current window. */
    private fun updateNeighbors() {
        val content = _state.value as? ReaderUiState.Content ?: return
        val list = _chapters.value
        if (list.isEmpty()) return
        val window = content.window
        val activePosition = list.indexOfFirst { it.id == window.active.chapterId }
        _previousChapter.value = if (activePosition >= 0) list.getOrNull(activePosition - 1) else null
        val next = if (activePosition >= 0) list.getOrNull(activePosition + 1) else null
        _nextChapter.value = if (next != null) NextChapterState.Next(next) else NextChapterState.None
        if (next != null) {
            if (next.id != prefetchedNextId) {
                prefetchedNextId = next.id
                nextPrefetchStarted.set(false)
            }
            prefetchNextManifest(next)
            maybePrefetchNext(currentLocal)
            // The chapter list may arrive after the reader already sits near the end.
            if (currentGlobal >= window.totalPages - APPEND_THRESHOLD) ensureNextLoaded()
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
                if (AppGraph.settings.lowMemory.value) {
                    ReaderPerf.log { "next_manifest_skipped_low_memory next=${next.id}" }
                    return@launch
                }
                val started = ReaderPerf.mark()
                ReaderPerf.log { "next_manifest_start next=${next.id}" }
                try {
                    AppGraph.comics.chapter(albumId, next.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The append will fetch it again if needed.
                }
                ReaderPerf.log {
                    "next_manifest_done next=${next.id} ms=${ReaderPerf.mark() - started}"
                }
            }
    }

    private fun maybePrefetchNext(page: Int) {
        val content = _state.value as? ReaderUiState.Content ?: return
        val chapter = content.window.active
        val next = (_nextChapter.value as? NextChapterState.Next)?.chapter ?: return
        if (chapter.pages.isEmpty()) return
        if (page < chapter.pages.size - NEXT_PREFETCH_THRESHOLD) return
        if (AppGraph.settings.lowMemory.value) return
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
                            // The reader will retry on demand when the append lands.
                        }
                    }
                }
                ReaderPerf.log {
                    "next_prefetch_done next=${next.id} ms=${ReaderPerf.mark() - started}"
                }
            }
    }

    private companion object {
        const val APPEND_THRESHOLD = 5
        const val NEXT_PREFETCH_THRESHOLD = 5
        const val NEXT_PREFETCH_PAGES = 5
        const val LOW_MEMORY_PREFETCH_AHEAD = 2
        const val LOW_MEMORY_PREFETCH_BEHIND = 1
    }
}
