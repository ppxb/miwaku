package com.ppxb.miwaku.ui.reader

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.core.data.settings.PageLayoutMode
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import com.ppxb.miwaku.ui.reader.viewer.NavigationAction
import com.ppxb.miwaku.ui.reader.viewer.PageLayout
import com.ppxb.miwaku.ui.reader.viewer.PagedViewer
import com.ppxb.miwaku.ui.reader.viewer.ReaderPageHolder
import com.ppxb.miwaku.ui.reader.viewer.Viewer
import com.ppxb.miwaku.ui.reader.viewer.ViewerNavigation
import com.ppxb.miwaku.ui.reader.viewer.WebtoonViewer
import com.ppxb.miwaku.ui.reader.viewer.rememberPagedViewerState
import com.ppxb.miwaku.ui.reader.viewer.rememberWebtoonViewerState
import com.ppxb.miwaku.ui.reader.chrome.ReaderBottomBar
import com.ppxb.miwaku.ui.reader.chrome.ReaderChapterSheet
import com.ppxb.miwaku.ui.reader.chrome.ReaderFooter
import com.ppxb.miwaku.ui.reader.chrome.ReaderPageIndicator
import com.ppxb.miwaku.ui.reader.chrome.ReaderSeekBar
import com.ppxb.miwaku.ui.reader.chrome.ReaderSettingsSheet
import com.ppxb.miwaku.ui.reader.chrome.ReaderTopBar
import com.ppxb.miwaku.ui.reader.chrome.medianPageRatio
import com.ppxb.miwaku.ui.reader.chrome.readerColorScheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/** Ratio used until a page reports its real size; roughly a JM page. */
private const val FALLBACK_RATIO = 0.7f

@Composable
internal fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val layoutMode by viewModel.layout.collectAsState()
    val sizes by viewModel.sizes.collectAsState()
    val albumTitle by viewModel.albumTitle.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val nextChapter by viewModel.nextChapter.collectAsState()
    val previousChapter by viewModel.previousChapter.collectAsState()

    val settings = AppGraph.settings
    val background by settings.readerBackground.collectAsState()
    val showPageNumber by settings.showPageNumber.collectAsState()
    val keepScreenOn by settings.keepScreenOn.collectAsState()
    val defaultMode by settings.defaultReadingMode.collectAsState()

    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    var chromeVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Viewer?>(null) }

    val content = state as? ReaderUiState.Content
    val pages = content?.pages ?: emptyList()
    val windowKey = pages.firstOrNull()?.key
    var currentPage by remember(windowKey) { mutableIntStateOf(content?.startPage ?: 0) }
    val activeChapterId = pages.getOrNull(currentPage)?.chapterId.orEmpty()
    val chapterName = chapters.firstOrNull { it.id == activeChapterId }?.name.orEmpty()
    // The scrubber and the counter are per chapter, like komikku: the window spans several chapters
    // but the reader never sees a cumulative page number.
    val chapterBounds =
        remember(pages, activeChapterId) {
            val start = pages.indexOfFirst { it.chapterId == activeChapterId }
            val end = pages.indexOfLast { it.chapterId == activeChapterId }
            if (start < 0 || end < start) 0 to 0 else start to (end - start + 1)
        }
    val activeStart = chapterBounds.first
    val activePageCount = chapterBounds.second
    val localPage =
        if (activePageCount == 0) 0 else (currentPage - activeStart).coerceIn(0, activePageCount - 1)
    val chapterTitles = remember(chapters) { chapters.associate { it.id to it.name } }
    val fallbackRatio = remember(sizes) { medianPageRatio(sizes.values) }

    val pageLayout =
        when (layoutMode) {
            PageLayoutMode.Automatic ->
                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    PageLayout.Double
                } else {
                    PageLayout.Single
                }

            PageLayoutMode.Single -> PageLayout.Single
            PageLayoutMode.Double -> PageLayout.Double
        }

    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Immersive reading: the bars follow the chrome, and leaving the reader restores them.
    val window = (view.context as? Activity)?.window
    DisposableEffect(window, view) {
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(window, view, chromeVisible) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        when {
            showSettings -> showSettings = false
            showChapters -> showChapters = false
            chromeVisible -> chromeVisible = false
            else -> onBack()
        }
    }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.readerColorScheme(background)) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val current = state) {
                ReaderUiState.Loading ->
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                is ReaderUiState.Failed ->
                    ErrorState(
                        message = current.message,
                        onRetry = { viewModel.load() },
                        modifier = Modifier.fillMaxSize(),
                    )

                is ReaderUiState.Content ->
                    key(mode, pageLayout) {
                        if (mode == ReadingMode.Webtoon) {
                            val viewerState =
                                rememberWebtoonViewerState(
                                    pages = current.pages,
                                    chapterTitles = chapterTitles,
                                    hasFooter = true,
                                    initialPage = currentPage,
                                )
                            LaunchedEffect(viewerState) { viewer = viewerState }
                            WebtoonViewer(
                                state = viewerState,
                                onPageChanged = { global ->
                                    currentPage = global
                                    viewModel.onPageVisible(global)
                                },
                                footer = { readerFooter(nextChapter, viewModel, onBack) },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .hazeSource(hazeState)
                                        .pointerInput(Unit) {
                                            detectTapGestures { offset ->
                                                val width = size.width.toFloat()
                                                val height = size.height.toFloat()
                                                val middleBand = offset.y < height * 2f / 3f
                                                val previous =
                                                    offset.y < height / 3f ||
                                                        (middleBand && offset.x < width / 3f)
                                                val next =
                                                    offset.y > height * 2f / 3f ||
                                                        (middleBand && offset.x > width * 2f / 3f)
                                                when {
                                                    previous ->
                                                        scope.launch {
                                                            viewerState.listState.animateScrollBy(-height * 0.85f)
                                                        }
                                                    next ->
                                                        scope.launch {
                                                            viewerState.listState.animateScrollBy(height * 0.85f)
                                                        }
                                                    else -> chromeVisible = !chromeVisible
                                                }
                                            }
                                        },
                            ) { page, box ->
                                ReaderPageHolder(
                                    page = page,
                                    imageWidthPx = widthPx,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = box.aspectRatio(ratioOf(page, sizes, fallbackRatio)),
                                    load = viewModel::loadPage,
                                )
                            }
                        } else {
                            val viewerState =
                                rememberPagedViewerState(
                                    pages = current.pages,
                                    layout = pageLayout,
                                    chapterTitles = chapterTitles,
                                    initialPage = currentPage,
                                    hasFooter = true,
                                )
                            LaunchedEffect(viewerState) { viewer = viewerState }
                            PagedViewer(
                                state = viewerState,
                                onPageChanged = { global ->
                                    currentPage = global
                                    viewModel.onPageVisible(global)
                                },
                                footer = { readerFooter(nextChapter, viewModel, onBack) },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .hazeSource(hazeState)
                                        .pointerInput(mode) {
                                            detectTapGestures { offset ->
                                                when (
                                                    ViewerNavigation.actionFor(
                                                        ViewerNavigation.zoneOf(offset.x, size.width.toFloat()),
                                                    )
                                                ) {
                                                    NavigationAction.Next ->
                                                        scope.launch { viewerState.moveNext() }
                                                    NavigationAction.Previous ->
                                                        scope.launch { viewerState.movePrevious() }
                                                    NavigationAction.Menu -> chromeVisible = !chromeVisible
                                                }
                                            }
                                        },
                                vertical = mode == ReadingMode.VerticalPager,
                                reverse = mode == ReadingMode.PagedRightToLeft,
                            ) { page, box ->
                                ReaderPageHolder(
                                    page = page,
                                    imageWidthPx = widthPx,
                                    contentScale = ContentScale.Fit,
                                    modifier = box,
                                    load = viewModel::loadPage,
                                )
                            }
                        }
                    }
            }

            AnimatedVisibility(
                visible = !chromeVisible && showPageNumber && activePageCount > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderPageIndicator(
                    page = localPage,
                    total = activePageCount,
                    hazeState = hazeState,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    title = albumTitle,
                    chapterName = chapterName,
                    hazeState = hazeState,
                    onBack = onBack,
                )
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReaderSeekBar(
                        page = localPage,
                        total = activePageCount,
                        showPageNumber = showPageNumber,
                        canGoPrevious = previousChapter != null,
                        canGoNext = nextChapter is NextChapterState.Next,
                        onPreviousChapter = viewModel::openPreviousChapter,
                        onNextChapter = viewModel::openNextChapter,
                        onSeek = { target ->
                            // The scrubber is per chapter; map it onto the window's global index.
                            scope.launch { viewer?.scrollToPage(activeStart + target) }
                        },
                        onSeekFinished = {},
                        hazeState = hazeState,
                    )
                    ReaderBottomBar(
                        hazeState = hazeState,
                        onOpenChapters = { showChapters = true },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }

        if (showSettings) {
            ReaderSettingsSheet(
                mode = mode,
                defaultMode = defaultMode,
                hasOverride = AppGraph.readerModes.modes.value[viewModel.albumId] != null,
                background = background,
                keepScreenOn = keepScreenOn,
                showPageNumber = showPageNumber,
                onModeSelect = viewModel::setMode,
                onBackgroundSelect = settings::setReaderBackground,
                onKeepScreenOnChange = settings::setKeepScreenOn,
                onShowPageNumberChange = settings::setShowPageNumber,
                onDismiss = { showSettings = false },
            )
        }
        if (showChapters) {
            ReaderChapterSheet(
                chapters = chapters,
                currentChapterId = activeChapterId,
                onSelect = { chapter ->
                    showChapters = false
                    viewModel.openChapter(chapter.id)
                },
                onDismiss = { showChapters = false },
            )
        }
    }
}

private fun ratioOf(
    page: ReaderPage,
    sizes: Map<String, PageSize>,
    fallback: Float,
): Float {
    val size = sizes[page.key] ?: return fallback
    if (size.width <= 0 || size.height <= 0) return fallback
    return size.width.toFloat() / size.height
}

/** End-of-window card: next chapter, retry, or "end of comic". */
@Composable
private fun readerFooter(
    nextChapter: NextChapterState,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    ReaderFooter(
        state = nextChapter,
        onRetry = viewModel::retryNextChapter,
        onBack = onBack,
        onOpenNext = viewModel::openNextChapter,
    )
}
