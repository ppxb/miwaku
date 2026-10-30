package com.ppxb.miwaku.ui.screen.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PagePriority
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.model.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ReaderAnchor(val key: String, val offset: Int)

internal data class ReaderWindow(
    val chapters: List<ChapterContent>,
    val activeIndex: Int,
    val id: Int,
    val revision: Int = 0,
    val anchor: ReaderAnchor? = null,
) {
    val active: ChapterContent get() = chapters[activeIndex]
    val images = chapters.flatMap { chapter -> chapter.pages.mapIndexed { index, page ->
        PageImage(chapter.chapterId, page.fileName.ifBlank { "page-" + index }, index, page.splitCount, page.url)
    } }
    val indexByKey = images.mapIndexed { index, image -> image.cacheKey to index }.toMap()
    val totalPages: Int get() = images.size
    fun startOf(index: Int) = chapters.take(index).sumOf { it.pages.size }
    fun locate(global: Int): Pair<Int, Int> {
        var remaining = global
        for ((index, chapter) in chapters.withIndex()) {
            if (remaining < chapter.pages.size) return index to remaining
            remaining -= chapter.pages.size
        }
        return chapters.lastIndex to chapters.last().pages.lastIndex.coerceAtLeast(0)
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
    val albumId: String = checkNotNull(savedStateHandle["albumId"])
    var chapterId: String = checkNotNull(savedStateHandle["chapterId"])
        private set
    var startPage: Int = if (savedStateHandle.get<Boolean>("restorePosition") == true) -2 else savedStateHandle["page"] ?: 0
        private set
    var startOffsetFraction = 0f
        private set
    var startPageKey: String? = null
        private set
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state = _state.asStateFlow()
    private val _chapters = MutableStateFlow<List<ChapterSummary>>(emptyList())
    val chapters = _chapters.asStateFlow()
    private val _albumTitle = MutableStateFlow("")
    val albumTitle = _albumTitle.asStateFlow()
    val pageSizes: StateFlow<Map<String, PageSize>> = AppGraph.pageStore.sizes
    private val _failedPages = MutableStateFlow<Set<String>>(emptySet())
    val failedPages = _failedPages.asStateFlow()
    private val _nextChapter = MutableStateFlow<NextChapterState>(NextChapterState.Unknown)
    val nextChapter = _nextChapter.asStateFlow()
    private val _previousChapter = MutableStateFlow<ChapterSummary?>(null)
    val previousChapter = _previousChapter.asStateFlow()
    val saveError = AppGraph.progress.error

    private var session = 0L
    private var windowId = 0
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private val neighbors = mutableMapOf<String, Job>()
    private val pendingNeighbors = mutableMapOf<String, ChapterContent>()
    private var requester: ReaderPrefetcher? = null
    private var currentGlobal = 0
    private var forward = true
    private var jumping = false
    private var target: PageImage? = null
    private val shown = mutableSetOf<String>()
    private var completedVisible = emptySet<String>()
    private var lastPosition: ReadingProgress? = null
    private var viewport: Viewport? = null

    private data class Viewport(
        val id: Int, val revision: Int, val visibleKeys: Set<String>, val anchorPage: String,
        val anchor: ReaderAnchor, val fraction: Float, val endKeys: Set<String>,
        val scrolling: Boolean, val canRecord: Boolean,
    )

    init { load() }

    fun openChapterInPlace(target: String, startAtEnd: Boolean = false) {
        if (target == chapterId && _state.value is ReaderUiState.Content) return
        chapterId = target
        startPage = if (startAtEnd) -1 else -2
        load(keepContent = true)
    }

    fun load(keepContent: Boolean = false) {
        val previous = (_state.value as? ReaderUiState.Content)?.window
        val previouslyShown = shown.toSet()
        loadJob?.cancel()
        detailJob?.cancel()
        neighbors.values.forEach { it.cancel() }
        neighbors.clear()
        pendingNeighbors.clear()
        requester?.cancel()
        requester = null
        jumping = true
        session = AppGraph.progress.beginSession()
        shown.clear()
        completedVisible = emptySet()
        lastPosition = null
        val requestedChapter = chapterId
        val requestedPage = startPage
        loadJob = viewModelScope.launch {
            if (!keepContent) _state.value = ReaderUiState.Loading
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, requestedChapter) }
                val saved = if (requestedPage == -2) AppGraph.progress.chapter(requestedChapter) else null
                val savedIndex = saved?.fileName?.let { name -> content.pages.indexOfFirst { it.fileName == name }.takeIf { it >= 0 } }
                val index = when {
                    requestedPage == -1 -> content.pages.lastIndex
                    requestedPage == -2 -> savedIndex ?: saved?.page ?: 0
                    else -> requestedPage
                }.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                startPage = index
                startOffsetFraction = saved?.offsetFraction ?: 0f
                currentGlobal = index
                viewport = null
                target = null
                shown.clear()
                _failedPages.value = emptySet()
                val window = ReaderWindow(listOf(content), 0, ++windowId)
                startPageKey = window.images.getOrNull(index)?.cacheKey
                _state.value = ReaderUiState.Content(window)
                jumping = false
                saveNavigation(content.chapterId, index)
                createRequester(window.id)
                // Prepare only the resume page and nearby metadata, never scan an entire chapter on entry.
                val initial = window.images.getOrNull(index)
                if (initial != null) {
                    target = initial
                    requester?.update(listOf(PageDemand(initial, PagePriority.Visible)))
                    launch(Dispatchers.IO) { AppGraph.pageStore.loadCachedSizes(window.images.drop(index).take(8)) }
                }
                resolveChapters()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                jumping = false
                if (previous != null && keepContent) {
                    shown += previouslyShown
                    chapterId = previous.active.chapterId
                    _state.value = ReaderUiState.Content(previous)
                    createRequester(previous.id)
                    updateDemand()
                    resolveChapters()
                } else _state.value = ReaderUiState.Failed("加载失败，请重试")
            }
        }
    }

    private fun createRequester(id: Int) {
        requester = ReaderPrefetcher(viewModelScope, AppGraph.pageStore,
            onPageReady = { image -> if (currentWindow()?.id == id) _failedPages.update { it - image.cacheKey } },
            onPageFailed = { image -> if (currentWindow()?.id == id) _failedPages.update { it + image.cacheKey } })
    }

    // Compatibility for initial navigation; only image + viewport confirmation records progress.
    fun onPageVisible(id: Int, global: Int) {
        val window = currentWindow()?.takeIf { it.id == id } ?: return
        if (global !in window.images.indices || jumping) return
        currentGlobal = global
    }

    fun onViewport(
        id: Int, revision: Int, visible: List<Int>, anchorPage: Int,
        anchorKey: String, offsetPx: Int, fraction: Float, ends: Set<Int>,
        scrolling: Boolean, canRecord: Boolean,
    ) {
        val window = currentWindow()?.takeIf { it.id == id && it.revision == revision } ?: return
        if (jumping || anchorPage !in window.images.indices) return
        if (anchorPage != currentGlobal) forward = anchorPage > currentGlobal
        currentGlobal = anchorPage
        val image = window.images[anchorPage]
        viewport = Viewport(id, revision, visible.mapNotNull { window.images.getOrNull(it)?.cacheKey }.toSet(),
            image.cacheKey, ReaderAnchor(anchorKey, offsetPx), fraction,
            ends.mapNotNull { window.images.getOrNull(it)?.cacheKey }.toSet(), scrolling, canRecord)
        if (target?.cacheKey in viewport!!.visibleKeys) target = null
        val (chapterIndex, _) = window.locate(anchorPage)
        if (window.activeIndex != chapterIndex) {
            chapterId = window.chapters[chapterIndex].chapterId
            _state.value = ReaderUiState.Content(window.copy(activeIndex = chapterIndex))
            updateNeighbors()
        }
        updateDemand()
        recordVisible()
        if (!scrolling) { applyPendingNeighbors(); trimWindow() }
    }

    fun imageDisplayed(id: Int, image: PageImage, successful: Boolean) {
        val window = currentWindow()?.takeIf { it.id == id && image.cacheKey in it.indexByKey } ?: return
        if (successful) shown += image.cacheKey else shown -= image.cacheKey
        if (successful) _failedPages.update { it - image.cacheKey }
        if (!jumping && window.revision == viewport?.revision) recordVisible()
    }

    private fun recordVisible() {
        val window = currentWindow() ?: return
        val view = viewport?.takeIf { it.id == window.id && it.revision == window.revision && it.canRecord } ?: return
        val image = window.indexByKey[view.anchorPage]?.let { window.images[it] } ?: return
        if (image.cacheKey in shown && image.cacheKey in view.visibleKeys) {
            val fraction = (view.fraction.coerceIn(0f, 1f) * 1000).toInt() / 1000f
            val position = ReadingProgress(image.chapterId, image.index, image.fileName, fraction)
            if (position != lastPosition) {
                AppGraph.progress.record(session, albumId, position)
                lastPosition = position
                saveNavigation(image.chapterId, image.index)
                savedStateHandle["restorePosition"] = true
            }
        }
        val ends = view.endKeys.filter { key ->
            key in shown && key in view.visibleKeys && window.images[window.indexByKey.getValue(key)].let { page ->
                window.chapters.first { it.chapterId == page.chapterId }.pages.lastIndex == page.index
            }
        }.toSet()
        (ends - completedVisible).forEach { key ->
            val page = window.images[window.indexByKey.getValue(key)]
            AppGraph.progress.record(session, albumId, ReadingProgress(page.chapterId, page.index, page.fileName,
                if (page.cacheKey == image.cacheKey) view.fraction else 0f), completed = true)
        }
        completedVisible = ends
    }

    private fun updateDemand() {
        val window = currentWindow() ?: return
        val visible = viewport?.visibleKeys.orEmpty().mapNotNull(window.indexByKey::get).ifEmpty { listOf(currentGlobal) }
        val demands = linkedMapOf<String, PageDemand>()
        fun add(index: Int, priority: PagePriority) {
            window.images.getOrNull(index)?.let { image -> demands.putIfAbsent(image.cacheKey, PageDemand(image, priority)) }
        }
        visible.forEach { add(it, PagePriority.Visible) }
        target?.let { demands[it.cacheKey] = PageDemand(it, PagePriority.Visible) }
        if (AppGraph.isPrefetchAllowed()) {
            val ahead = if (AppGraph.settings.lowMemory.value) 2 else AppGraph.settings.readerPrefetchAhead.value
            val behind = if (AppGraph.settings.lowMemory.value) 1 else 2
            val first = visible.minOrNull() ?: currentGlobal
            val last = visible.maxOrNull() ?: currentGlobal
            for (distance in 1..ahead) add(if (forward) last + distance else first - distance, PagePriority.Nearby)
            for (distance in 1..behind) add(if (forward) first - distance else last + distance, PagePriority.Behind)
        }
        requester?.update(demands.values.toList())
    }

    fun fileOf(image: PageImage): File? = AppGraph.pageStore.cachedFile(image)

    fun ensurePage(image: PageImage) {
        target = image
        updateDemand()
    }

    fun retry(image: PageImage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppGraph.pageStore.invalidate(image) }
            requester?.retry(image.cacheKey)
            _failedPages.update { it - image.cacheKey }
            ensurePage(image)
        }
    }

    fun flush() { AppGraph.progress.flushSession(session) }

    fun retryNextChapter() {
        val next = (_nextChapter.value as? NextChapterState.Next)?.chapter
        if (next == null) resolveChapters() else loadNeighbor(next)
    }

    private fun resolveChapters() {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { AppGraph.comics.detail(albumId) }
                _chapters.value = detail.chapters
                _albumTitle.value = detail.title
                updateNeighbors()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The loaded/offline chapter stays readable. */ }
        }
    }

    private fun updateNeighbors() {
        val window = currentWindow() ?: return
        val list = _chapters.value
        val active = list.indexOfFirst { it.id == window.active.chapterId }
        if (active < 0) return
        _previousChapter.value = list.getOrNull(active - 1)
        _nextChapter.value = list.getOrNull(active + 1)?.let { NextChapterState.Next(it) } ?: NextChapterState.None
        if (AppGraph.isPrefetchAllowed()) {
            list.getOrNull(active - 1)?.let(::loadNeighbor)
            list.getOrNull(active + 1)?.let(::loadNeighbor)
        }
    }

    private fun loadNeighbor(chapter: ChapterSummary) {
        val original = currentWindow() ?: return
        if (original.chapters.any { it.chapterId == chapter.id } || neighbors[chapter.id]?.isActive == true) return
        neighbors[chapter.id] = viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { AppGraph.comics.chapter(albumId, chapter.id) }
                val current = currentWindow()?.takeIf { it.id == original.id } ?: return@launch
                val list = _chapters.value
                val center = list.indexOfFirst { it.id == current.active.chapterId }
                if (content.pages.isEmpty() || list.indexOfFirst { it.id == chapter.id } !in center - 1..center + 1) return@launch
                val chapters = (current.chapters + content).distinctBy { it.chapterId }
                    .sortedBy { item -> list.indexOfFirst { it.id == item.chapterId } }
                if (chapters.first().chapterId != current.chapters.first().chapterId && viewport?.scrolling == true) {
                    pendingNeighbors[content.chapterId] = content
                } else publish(current, chapters)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Explicit navigation retries failed neighbors. */ }
            finally { if (neighbors[chapter.id] === currentCoroutineContext()[Job]) neighbors.remove(chapter.id) }
        }
    }

    private fun trimWindow() {
        val current = currentWindow() ?: return
        if (current.chapters.size <= 3) return
        val list = _chapters.value
        val center = list.indexOfFirst { it.id == current.active.chapterId }
        val keep = listOfNotNull(list.getOrNull(center - 1)?.id, current.active.chapterId, list.getOrNull(center + 1)?.id).toMutableSet()
        viewport?.visibleKeys.orEmpty().forEach { key -> current.indexByKey[key]?.let { keep += current.images[it].chapterId } }
        target?.let { keep += it.chapterId }
        val chapters = current.chapters.filter { it.chapterId in keep }
        if (chapters.size < current.chapters.size) publish(current, chapters)
    }

    private fun applyPendingNeighbors() {
        if (pendingNeighbors.isEmpty()) return
        val current = currentWindow() ?: return
        val catalog = _chapters.value
        val center = catalog.indexOfFirst { it.id == current.active.chapterId }
        val added = pendingNeighbors.values.filter { chapter ->
            catalog.indexOfFirst { it.id == chapter.chapterId } in center - 1..center + 1
        }
        pendingNeighbors.clear()
        if (added.isNotEmpty()) publish(current, (current.chapters + added).distinctBy { it.chapterId }
            .sortedBy { chapter -> catalog.indexOfFirst { it.id == chapter.chapterId } })
    }

    private fun publish(previous: ReaderWindow, chapters: List<ChapterContent>) {
        val currentKey = previous.images.getOrNull(currentGlobal)?.cacheKey
        val appended = chapters.take(previous.chapters.size).map { it.chapterId } == previous.chapters.map { it.chapterId }
        val pendingAnchor = previous.anchor?.takeIf { viewport?.revision != previous.revision }
        val next = ReaderWindow(chapters, chapters.indexOfFirst { it.chapterId == previous.active.chapterId }.coerceAtLeast(0),
            previous.id, previous.revision + 1, if (appended) pendingAnchor else pendingAnchor ?: viewport?.anchor ?: previous.images.getOrNull(currentGlobal)?.let {
                ReaderAnchor("p:" + it.chapterId + ":" + it.index + ":" + it.fileName, 0)
            })
        currentGlobal = next.indexByKey[currentKey] ?: 0
        _state.value = ReaderUiState.Content(next)
        val kept = next.indexByKey.keys
        shown.retainAll(kept)
        _failedPages.update { it.intersect(kept) }
        updateDemand()
    }

    private fun currentWindow() = (_state.value as? ReaderUiState.Content)?.window
    private fun saveNavigation(chapter: String, page: Int) {
        savedStateHandle["chapterId"] = chapter
        savedStateHandle["page"] = page
    }

    override fun onCleared() {
        requester?.cancel()
        AppGraph.progress.flushSession(session, end = true)
        super.onCleared()
    }
}
