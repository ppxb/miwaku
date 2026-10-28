package com.ppxb.miwaku.ui.screen.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.core.data.settings.ReaderBackground
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.reader.image.PageImageRequests
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.labelRes
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun ReaderScreen(
    onBack: () -> Unit,
    onNextChapter: (albumId: String, chapterId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val readerBackground by AppGraph.settings.readerBackground.collectAsState()
    val keepScreenOn by AppGraph.settings.keepScreenOn.collectAsState()
    val showPageNumber by AppGraph.settings.showPageNumber.collectAsState()
    val appColorScheme = MaterialTheme.colorScheme
    // Keyed on the app scheme as well: black/paper reuse its non-colour tokens as the base.
    val readerScheme = remember(readerBackground, appColorScheme) {
        appColorScheme.readerColorScheme(readerBackground)
    }
    val content = (state as? ReaderUiState.Content)?.content
    // Detail owns the album name; the chapter payload name drifts per chapter.
    val albumTitle by viewModel.albumTitle.collectAsState()
    val title =
        albumTitle.ifBlank { content?.title.orEmpty() }.ifBlank {
            stringResource(R.string.reader_title)
        }
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var readerPage by rememberSaveable { mutableIntStateOf(viewModel.initialPage) }
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val modeOverrides by AppGraph.readerModes.modes.collectAsState()
    val defaultMode by AppGraph.settings.defaultReadingMode.collectAsState()
    val mode = modeOverrides[viewModel.albumId] ?: defaultMode
    val chapters by viewModel.chapters.collectAsState()
    // Detail already fills in "第N话" when the source chapter name is empty.
    val chapterName = chapters.firstOrNull { it.id == viewModel.chapterId }?.name.orEmpty()
    val pageSizes by viewModel.pageSizes.collectAsState()
    val failedPages by viewModel.failedPages.collectAsState()
    val nextChapter by viewModel.nextChapter.collectAsState()
    val previousChapter by viewModel.previousChapter.collectAsState()
    val openNextChapter: () -> Unit = {
        val loaded = (state as? ReaderUiState.Content)?.content
        val next = (nextChapter as? NextChapterState.Next)?.chapter
        if (loaded != null && next != null) onNextChapter(loaded.albumId, next.id)
    }
    val openPreviousChapter: () -> Unit = {
        val loaded = (state as? ReaderUiState.Content)?.content
        val previous = previousChapter
        if (loaded != null && previous != null) onNextChapter(loaded.albumId, previous.id)
    }
    // Warm the target page so seeking does not land on an empty placeholder.
    val prefetchPage: (Int) -> Unit = { target ->
        val loaded = (state as? ReaderUiState.Content)?.content
        if (loaded != null && target in loaded.pages.indices) {
            val page = loaded.pages[target]
            viewModel.ensurePage(
                PageImage(
                    chapterId = loaded.chapterId,
                    fileName = page.fileName.ifBlank { "page-$target" },
                    index = target,
                    splitCount = page.splitCount,
                    url = page.url,
                ),
            )
        }
    }
    val navigating = remember { mutableStateOf(false) }
    val currentContent = rememberUpdatedState(content)
    val currentNextChapter = rememberUpdatedState(nextChapter)
    val currentOnNextChapter = rememberUpdatedState(onNextChapter)
    val overscrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val pastTop =
                        listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > 0
                    if (source == NestedScrollSource.UserInput &&
                        available.y < 0f &&
                        pastTop &&
                        listState.layoutInfo.totalItemsCount > 0 &&
                        !listState.canScrollForward
                    ) {
                        val next = (currentNextChapter.value as? NextChapterState.Next)?.chapter
                        val chapter = currentContent.value
                        if (next != null && chapter != null && !navigating.value) {
                            navigating.value = true
                            ReaderPerf.log {
                                "chapter_overscroll from=${chapter.chapterId} to=${next.id}"
                            }
                            currentOnNextChapter.value(chapter.albumId, next.id)
                        }
                    }
                    return Offset.Zero
                }
            }
        }
    val fallbackRatio =
        remember(pageSizes, content) {
            val chapterId = content?.chapterId
            val sizes =
                if (chapterId == null) {
                    emptyList()
                } else {
                    content.pages.mapIndexedNotNull { index, page ->
                        pageSizes["$chapterId/${page.fileName.ifBlank { "page-$index" }}"]
                    }
                }
            medianPageRatio(sizes)
        }
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val imageWidthPx =
        remember(screenWidthDp, density.density) {
            (screenWidthDp * density.density).roundToInt().coerceAtLeast(1)
        }
    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    DisposableEffect(window, view) {
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(window, view, controlsVisible) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controlsVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // Webtoon keeps its own scroll; paged modes report their page through onPageChanged.
    LaunchedEffect(content?.chapterId, mode) {
        if (mode != ReadingMode.Webtoon) return@LaunchedEffect
        val pages = content?.pages ?: return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        val target = readerPage.coerceIn(0, pages.lastIndex)
        // A restored list already sits on the page; scrolling again would drop its inner offset.
        if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                readerPage = it
                viewModel.recordPage(it)
            }
    }
    // Paged modes consume the jump themselves; this only serves the webtoon list.
    LaunchedEffect(pendingJump, mode) {
        if (mode != ReadingMode.Webtoon) return@LaunchedEffect
        val target = pendingJump ?: return@LaunchedEffect
        val pages = content?.pages ?: return@LaunchedEffect
        if (pages.isNotEmpty()) listState.scrollToItem(target.coerceIn(0, pages.lastIndex))
        pendingJump = null
    }

    Box(modifier.fillMaxSize()) {
        // Bars keep the app theme; everything under them follows the chosen reader background.
        MaterialTheme(colorScheme = readerScheme) {
            Box(Modifier.fillMaxSize().background(readerScheme.background)) {
                when (val current = state) {
                    ReaderUiState.Loading ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                    is ReaderUiState.Failed ->
                        ErrorState(
                            message = current.message,
                            onRetry = viewModel::load,
                            modifier = Modifier.fillMaxSize(),
                        )

                    is ReaderUiState.Content ->
                        if (current.content.pages.isEmpty()) {
                            EmptyState(
                                icon = Icons.Rounded.AutoStories,
                                title = stringResource(R.string.reader_empty_title),
                                description = stringResource(R.string.reader_empty_description),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (mode == ReadingMode.Webtoon) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(overscrollConnection),
                            ) {
                                ReaderPages(
                                    content = current.content,
                                    listState = listState,
                                    pageSizes = pageSizes,
                                    failedPages = failedPages,
                                    fallbackRatio = fallbackRatio,
                                    imageWidthPx = imageWidthPx,
                                    onFileOf = viewModel::fileOf,
                                    onRetry = viewModel::retry,
                                    nextChapter = nextChapter,
                                    onRetryNext = viewModel::retryNextChapter,
                                    onOpenNextChapter = openNextChapter,
                                    onBack = onBack,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
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
                                                                listState.animateScrollBy(-height * 0.85f)
                                                            }
                                                        next ->
                                                            scope.launch {
                                                                listState.animateScrollBy(height * 0.85f)
                                                            }
                                                        else -> controlsVisible = !controlsVisible
                                                    }
                                                }
                                            }
                                            .hazeSource(hazeState),
                                )
                            }
                        } else {
                            PagedReader(
                                content = current.content,
                                mode = mode,
                                startPage = readerPage,
                                pageSizes = pageSizes,
                                failedPages = failedPages,
                                imageWidthPx = imageWidthPx,
                                onFileOf = viewModel::fileOf,
                                onRetry = viewModel::retry,
                                onPageChanged = { page ->
                                    readerPage = page
                                    viewModel.recordPage(page)
                                },
                                nextChapter = nextChapter,
                                onRetryNext = viewModel::retryNextChapter,
                                onOpenNextChapter = openNextChapter,
                                jumpToPage = pendingJump,
                                onJumpConsumed = { pendingJump = null },
                                onBack = onBack,
                                onToggleMenu = { controlsVisible = !controlsVisible },
                                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                            )
                        }
                }
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ReaderTopBar(
                title = title,
                chapterName = chapterName,
                hazeState = hazeState,
                onBack = onBack,
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderSeekBar(
                    page = readerPage,
                    total = content?.pages?.size ?: 0,
                    showPageNumber = showPageNumber,
                    canGoPrevious = previousChapter != null,
                    canGoNext = nextChapter is NextChapterState.Next,
                    onPreviousChapter = openPreviousChapter,
                    onNextChapter = openNextChapter,
                    onSeek = { target ->
                        prefetchPage(target)
                        pendingJump = target
                    },
                    hazeState = hazeState,
                )
                ReaderBottomBar(
                    hazeState = hazeState,
                    onOpenChapters = { showChapters = true },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
        // While the bars are hidden the page counter floats on its own, blurred over the page.
        AnimatedVisibility(
            visible = !controlsVisible && showPageNumber && content?.pages?.isNotEmpty() == true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ReaderPageIndicator(
                page = readerPage,
                total = content?.pages?.size ?: 0,
                hazeState = hazeState,
            )
        }
    }
    if (showSettings) {
        ReaderSettingsSheet(
            mode = mode,
            defaultMode = defaultMode,
            hasOverride = modeOverrides.containsKey(viewModel.albumId),
            background = readerBackground,
            keepScreenOn = keepScreenOn,
            showPageNumber = showPageNumber,
            onModeSelect = { selected ->
                AppGraph.readerModes.setMode(viewModel.albumId, selected)
            },
            onBackgroundSelect = AppGraph.settings::setReaderBackground,
            onKeepScreenOnChange = AppGraph.settings::setKeepScreenOn,
            onShowPageNumberChange = AppGraph.settings::setShowPageNumber,
            onDismiss = { showSettings = false },
        )
    }
    if (showChapters) {
        ReaderChapterSheet(
            chapters = chapters,
            currentChapterId = viewModel.chapterId,
            onSelect = { chapter ->
                showChapters = false
                if (chapter.id != viewModel.chapterId) {
                    onNextChapter(viewModel.albumId, chapter.id)
                }
            },
            onDismiss = { showChapters = false },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    chapterName: String,
    hazeState: HazeState,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                .statusBarsPadding(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = chapterName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Page scrubber that floats above the control bar, matching the reference reader: previous
 * chapter, page, slider, total, next chapter. Seeking applies while dragging.
 */
@Composable
private fun ReaderSeekBar(
    page: Int,
    total: Int,
    showPageNumber: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Int) -> Unit,
    hazeState: HazeState,
) {
    val lastIndex = (total - 1).coerceAtLeast(0)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(page.toFloat().coerceIn(0f, lastIndex.toFloat())) }
    var lastRequested by remember { mutableIntStateOf(-1) }
    val displayPage =
        if (scrubbing) scrubValue.roundToInt().coerceIn(0, lastIndex) else page.coerceIn(0, lastIndex)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle(alpha = 0.9f, radius = 18.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousChapter, enabled = canGoPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.reader_previous_chapter),
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (canGoPrevious) 1f else 0.35f,
                    ),
            )
        }
        if (showPageNumber) {
            Text(
                text = (displayPage + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 24.dp),
            )
        }
        Slider(
            value = if (scrubbing) scrubValue else displayPage.toFloat(),
            onValueChange = { raw ->
                scrubbing = true
                scrubValue = raw
                val target = raw.roundToInt().coerceIn(0, lastIndex)
                if (target != lastRequested) {
                    lastRequested = target
                    onSeek(target)
                }
            },
            onValueChangeFinished = {
                scrubbing = false
                val target = scrubValue.roundToInt().coerceIn(0, lastIndex)
                if (target != lastRequested) onSeek(target)
                lastRequested = -1
            },
            valueRange = 0f..lastIndex.toFloat(),
            enabled = total > 1,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        if (showPageNumber) {
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 24.dp),
            )
        }
        IconButton(onClick = onNextChapter, enabled = canGoNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.reader_next_chapter),
                tint =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (canGoNext) 1f else 0.35f,
                    ),
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    hazeState: HazeState,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                .navigationBarsPadding()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenChapters) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Toc,
                contentDescription = stringResource(R.string.reader_chapters),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.reader_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Page counter that floats over the page while the bars are hidden. */
@Composable
private fun ReaderPageIndicator(
    page: Int,
    total: Int,
    hazeState: HazeState,
) {
    Box(Modifier.navigationBarsPadding().padding(bottom = 18.dp)) {
        Text(
            text = stringResource(
                R.string.reader_page_compact,
                if (total > 0) page.coerceIn(0, total - 1) + 1 else 1,
                total,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .hazeBlur(
                        HazeInput.Sources(hazeState),
                        topBarBlurStyle(alpha = 0.9f, radius = 18.dp),
                    ).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ReaderPages(
    content: ChapterContent,
    listState: LazyListState,
    pageSizes: Map<String, PageSize>,
    failedPages: Set<String>,
    fallbackRatio: Float,
    imageWidthPx: Int,
    onFileOf: (PageImage) -> File?,
    onRetry: (PageImage) -> Unit,
    nextChapter: NextChapterState,
    onRetryNext: () -> Unit,
    onOpenNextChapter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(
            items = content.pages,
            key = { index, page -> "$index:${page.fileName}" },
            contentType = { _, _ -> "page" },
        ) { index, page ->
            val image =
                remember(content.chapterId, page.url, page.fileName, page.splitCount, index) {
                    PageImage(
                        chapterId = content.chapterId,
                        fileName = page.fileName.ifBlank { "page-$index" },
                        index = index,
                        splitCount = page.splitCount,
                        url = page.url,
                    )
                }
            ReaderPageImage(
                image = image,
                index = index,
                knownSize = pageSizes[image.cacheKey],
                failed = image.cacheKey in failedPages,
                fallbackRatio = fallbackRatio,
                imageWidthPx = imageWidthPx,
                fileOf = onFileOf,
                retry = onRetry,
            )
        }
        item(key = "reader-footer", contentType = "footer") {
            ReaderFooter(
                state = nextChapter,
                onRetry = onRetryNext,
                onBack = onBack,
                onOpenNext = onOpenNextChapter,
            )
        }
    }
}

@Composable
private fun ReaderPageImage(
    image: PageImage,
    index: Int,
    knownSize: PageSize?,
    failed: Boolean,
    fallbackRatio: Float,
    imageWidthPx: Int,
    fileOf: (PageImage) -> File?,
    retry: (PageImage) -> Unit,
) {
    val ratio =
        if (knownSize != null && knownSize.width > 0 && knownSize.height > 0) {
            knownSize.width.toFloat() / knownSize.height
        } else {
            fallbackRatio
        }
    ReaderPageArtwork(
        image = image,
        index = index,
        knownSize = knownSize,
        failed = failed,
        imageWidthPx = imageWidthPx,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        fileOf = fileOf,
        retry = retry,
    )
}

@Composable
private fun ReaderPageArtwork(
    image: PageImage,
    index: Int,
    knownSize: PageSize?,
    failed: Boolean,
    imageWidthPx: Int,
    contentScale: ContentScale,
    modifier: Modifier,
    fileOf: (PageImage) -> File?,
    retry: (PageImage) -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var decodeFailed by remember { mutableStateOf(false) }
    val file = remember(image.cacheKey, knownSize) { fileOf(image) }
    val pageStarted = remember(image.cacheKey, attempt, file) { ReaderPerf.mark() }
    val request =
        remember(file, image, imageWidthPx, attempt) {
            file?.let { PageImageRequests.build(context, image, it, imageWidthPx, attempt) }
        }
    val painter = rememberAsyncImagePainter(model = request)
    val painterState = painter.state
    var showSpinner by remember(request) { mutableStateOf(false) }

    // The spinner follows the painter state, so it can never outlive a settled page.
    LaunchedEffect(painterState) {
        if (painterState is AsyncImagePainter.State.Loading) {
            delay(180)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }
    LaunchedEffect(painterState) {
        when (painterState) {
            is AsyncImagePainter.State.Success -> {
                decodeFailed = false
                ReaderPerf.log {
                    "page_ready index=$index ms=${ReaderPerf.mark() - pageStarted}"
                }
            }
            is AsyncImagePainter.State.Error -> {
                decodeFailed = true
                ReaderPerf.log { "page_decode_failed index=$index" }
            }
            else -> Unit
        }
    }

    Box(
        modifier =
            modifier
                .clipToBounds()
                // Match the canvas so a page that is still decoding does not flash a dark block.
                .background(MaterialTheme.colorScheme.background),
    ) {
        Image(
            painter = painter,
            contentDescription = stringResource(R.string.reader_page, index + 1),
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        if (showSpinner && !failed && !decodeFailed) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                strokeWidth = 2.dp,
            )
        }
        if (failed || decodeFailed) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.error_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        retry(image)
                        decodeFailed = false
                        attempt += 1
                    },
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun ReaderFooter(
    state: NextChapterState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenNext: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            NextChapterState.Unknown -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.reader_next_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.reader_back_to_detail))
                    }
                }
            }

            NextChapterState.None -> {
                HorizontalDivider(
                    modifier = Modifier.width(72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.reader_end_of_comic),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is NextChapterState.Next -> {
                Text(
                    text = stringResource(R.string.reader_next_up),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.chapter.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenNext) {
                    Text(stringResource(R.string.reader_open_next))
                }
            }
        }
    }
}

private fun medianPageRatio(sizes: Collection<PageSize>): Float {
    if (sizes.size < MIN_RATIO_SAMPLES) return DEFAULT_PAGE_RATIO
    val ratios =
        sizes
            .mapNotNull { size ->
                if (size.width > 0 && size.height > 0) {
                    size.width.toFloat() / size.height
                } else {
                    null
                }
            }.sorted()
    if (ratios.size < MIN_RATIO_SAMPLES) return DEFAULT_PAGE_RATIO
    return ratios[ratios.size / 2]
}

/**
 * Reader-local colors. The bars stay on the app theme, so only the page canvas and whatever is
 * painted on it gets re-tinted; without this, a fixed canvas would keep theme text that can
 * vanish on it (dark theme + paper, light theme + black).
 */
private fun ColorScheme.readerColorScheme(background: ReaderBackground): ColorScheme =
    when (background) {
        ReaderBackground.MatchTheme -> this
        ReaderBackground.Black ->
            copy(
                background = BlackCanvas,
                onBackground = BlackInk,
                surface = BlackCanvas,
                onSurface = BlackInk,
                surfaceVariant = BlackContainer,
                onSurfaceVariant = BlackInkVariant,
                surfaceContainer = BlackContainer,
                primary = BlackInk,
                onPrimary = BlackCanvas,
                primaryContainer = BlackContainer,
                onPrimaryContainer = BlackInk,
                outlineVariant = BlackOutline,
            )
        ReaderBackground.Paper ->
            copy(
                background = PaperCanvas,
                onBackground = PaperInk,
                surface = PaperCanvas,
                onSurface = PaperInk,
                surfaceVariant = PaperContainer,
                onSurfaceVariant = PaperInkVariant,
                surfaceContainer = PaperContainer,
                primary = PaperAccent,
                onPrimary = PaperCanvas,
                primaryContainer = PaperContainer,
                onPrimaryContainer = PaperInk,
                outlineVariant = PaperOutline,
            )
    }

private val BlackCanvas = Color.Black
private val BlackContainer = Color(0xFF171717)
private val BlackInk = Color(0xFFEDEDED)
private val BlackInkVariant = Color(0xFFB4B4B4)
private val BlackOutline = Color(0xFF2E2E2E)

private val PaperCanvas = Color(0xFFF4EDE1)
private val PaperContainer = Color(0xFFE7DDCB)
private val PaperInk = Color(0xFF2A241C)
private val PaperInkVariant = Color(0xFF5C5344)
private val PaperAccent = Color(0xFF6B4A2B)
private val PaperOutline = Color(0xFFD5C9B4)

private const val DEFAULT_PAGE_RATIO = 3f / 4f
private const val MIN_RATIO_SAMPLES = 3

@Composable
private fun PagedReader(
    content: ChapterContent,
    mode: ReadingMode,
    startPage: Int,
    pageSizes: Map<String, PageSize>,
    failedPages: Set<String>,
    imageWidthPx: Int,
    onFileOf: (PageImage) -> File?,
    onRetry: (PageImage) -> Unit,
    onPageChanged: (Int) -> Unit,
    nextChapter: NextChapterState,
    onRetryNext: () -> Unit,
    onOpenNextChapter: () -> Unit,
    jumpToPage: Int?,
    onJumpConsumed: () -> Unit,
    onBack: () -> Unit,
    onToggleMenu: () -> Unit,
    modifier: Modifier,
) {
    val pages = content.pages
    val state = rememberPagerState(
        initialPage = startPage.coerceIn(0, pages.lastIndex),
    ) { pages.size + 1 }
    val scope = rememberCoroutineScope()
    val rightToLeft = mode == ReadingMode.PagedRightToLeft
    // Tap zones mirror the reference reader: thirds turn the page, the middle opens the menu.
    val pagerModifier =
        modifier.pointerInput(mode) {
            detectTapGestures { offset ->
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                fun turn(target: Int) {
                    scope.launch {
                        state.animateScrollToPage(target.coerceIn(0, pages.lastIndex))
                    }
                }
                when (mode) {
                    ReadingMode.VerticalPager ->
                        when {
                            offset.y < height / 3f -> turn(state.currentPage - 1)
                            offset.y > height * 2f / 3f -> turn(state.currentPage + 1)
                            else -> onToggleMenu()
                        }
                    else ->
                        when {
                            offset.x < width / 3f -> turn(state.currentPage + if (rightToLeft) 1 else -1)
                            offset.x > width * 2f / 3f -> turn(state.currentPage + if (rightToLeft) -1 else 1)
                            else -> onToggleMenu()
                        }
                }
            }
        }

    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }
            .distinctUntilChanged()
            .collect { page -> if (page < pages.size) onPageChanged(page) }
    }
    LaunchedEffect(state, jumpToPage) {
        val target = jumpToPage ?: return@LaunchedEffect
        if (target in pages.indices) state.scrollToPage(target)
        onJumpConsumed()
    }

    val pageContent: @Composable (Int) -> Unit = { index ->
        if (index < pages.size) {
            PagedPage(
                index = index,
                content = content,
                pageSizes = pageSizes,
                failedPages = failedPages,
                imageWidthPx = imageWidthPx,
                onFileOf = onFileOf,
                onRetry = onRetry,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ReaderFooter(
                    state = nextChapter,
                    onRetry = onRetryNext,
                    onBack = onBack,
                    onOpenNext = onOpenNextChapter,
                )
            }
        }
    }

    if (mode == ReadingMode.VerticalPager) {
        VerticalPager(state = state, modifier = pagerModifier, key = { it }) { index ->
            pageContent(index)
        }
    } else {
        HorizontalPager(
            state = state,
            modifier = pagerModifier,
            reverseLayout = mode == ReadingMode.PagedRightToLeft,
            key = { it },
        ) { index ->
            pageContent(index)
        }
    }
}

@Composable
private fun PagedPage(
    index: Int,
    content: ChapterContent,
    pageSizes: Map<String, PageSize>,
    failedPages: Set<String>,
    imageWidthPx: Int,
    onFileOf: (PageImage) -> File?,
    onRetry: (PageImage) -> Unit,
) {
    val page = content.pages[index]
    val image =
        remember(content.chapterId, page.url, page.fileName, page.splitCount, index) {
            PageImage(
                chapterId = content.chapterId,
                fileName = page.fileName.ifBlank { "page-$index" },
                index = index,
                splitCount = page.splitCount,
                url = page.url,
            )
        }
    ReaderPageArtwork(
        image = image,
        index = index,
        knownSize = pageSizes[image.cacheKey],
        failed = image.cacheKey in failedPages,
        imageWidthPx = imageWidthPx,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        fileOf = onFileOf,
        retry = onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    mode: ReadingMode,
    defaultMode: ReadingMode,
    hasOverride: Boolean,
    background: ReaderBackground,
    keepScreenOn: Boolean,
    showPageNumber: Boolean,
    onModeSelect: (ReadingMode?) -> Unit,
    onBackgroundSelect: (ReaderBackground) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onShowPageNumberChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_settings),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.reader_settings_tab_reading)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.reader_settings_tab_display)) },
                )
            }
            Spacer(Modifier.height(18.dp))
            if (tab == 0) {
                ReaderReadingSettings(
                    mode = mode,
                    defaultMode = defaultMode,
                    hasOverride = hasOverride,
                    background = background,
                    onModeSelect = onModeSelect,
                    onBackgroundSelect = onBackgroundSelect,
                )
            } else {
                ReaderSettingSwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
                ReaderSettingSwitchRow(
                    title = stringResource(R.string.settings_show_page_number),
                    checked = showPageNumber,
                    onCheckedChange = onShowPageNumberChange,
                )
            }
        }
    }
}

@Composable
private fun ReaderReadingSettings(
    mode: ReadingMode,
    defaultMode: ReadingMode,
    hasOverride: Boolean,
    background: ReaderBackground,
    onModeSelect: (ReadingMode?) -> Unit,
    onBackgroundSelect: (ReaderBackground) -> Unit,
) {
    ReaderSettingsSection(title = stringResource(R.string.reader_mode_title))
    ReaderModeTile(
        mode = null,
        defaultMode = defaultMode,
        selected = !hasOverride,
        onClick = { onModeSelect(null) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderModeTile(
            mode = ReadingMode.Webtoon,
            defaultMode = defaultMode,
            selected = hasOverride && mode == ReadingMode.Webtoon,
            onClick = { onModeSelect(ReadingMode.Webtoon) },
            modifier = Modifier.weight(1f),
        )
        ReaderModeTile(
            mode = ReadingMode.PagedLeftToRight,
            defaultMode = defaultMode,
            selected = hasOverride && mode == ReadingMode.PagedLeftToRight,
            onClick = { onModeSelect(ReadingMode.PagedLeftToRight) },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderModeTile(
            mode = ReadingMode.PagedRightToLeft,
            defaultMode = defaultMode,
            selected = hasOverride && mode == ReadingMode.PagedRightToLeft,
            onClick = { onModeSelect(ReadingMode.PagedRightToLeft) },
            modifier = Modifier.weight(1f),
        )
        ReaderModeTile(
            mode = ReadingMode.VerticalPager,
            defaultMode = defaultMode,
            selected = hasOverride && mode == ReadingMode.VerticalPager,
            onClick = { onModeSelect(ReadingMode.VerticalPager) },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(20.dp))
    ReaderSettingsSection(title = stringResource(R.string.settings_reader_background))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderBackground.entries.forEach { option ->
            ReaderBackgroundTile(
                background = option,
                selected = option == background,
                onClick = { onBackgroundSelect(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReaderSettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun ReaderModeTile(
    mode: ReadingMode?,
    defaultMode: ReadingMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        if (mode == null) {
            stringResource(R.string.reader_mode_follow_default, stringResource(defaultMode.labelRes()))
        } else {
            stringResource(mode.labelRes())
        }
    ReaderOptionTile(label = label, selected = selected, onClick = onClick, modifier = modifier)
}

@Composable
private fun ReaderBackgroundTile(
    background: ReaderBackground,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderOptionTile(
        label = stringResource(background.labelRes()),
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ReaderOptionTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReaderSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderChapterSheet(
    chapters: List<ChapterSummary>,
    currentChapterId: String,
    onSelect: (ChapterSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentIndex =
        remember(chapters, currentChapterId) {
            chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.reader_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (chapters.isEmpty()) {
                Text(
                    text = stringResource(R.string.reader_chapters_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex),
                    // Keep the sheet around the same height as the settings panel instead of
                    // swallowing the whole reader.
                    modifier = Modifier.fillMaxWidth().heightIn(max = 375.dp),
                ) {
                    items(chapters, key = { it.id }) { chapter ->
                        val selected = chapter.id == currentChapterId
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(chapter) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = stringResource(R.string.reader_chapters_current),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
