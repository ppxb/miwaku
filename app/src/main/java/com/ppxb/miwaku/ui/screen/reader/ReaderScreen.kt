package com.ppxb.miwaku.ui.screen.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ReaderPage
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val content = (state as? ReaderUiState.Content)?.content
    val title =
        content?.title?.ifBlank { stringResource(R.string.reader_title) }
            ?: stringResource(R.string.reader_title)
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val pageSizes = remember { mutableStateMapOf<String, IntSize>() }
    val fallbackRatio = remember(pageSizes.size) { medianPageRatio(pageSizes.values) }
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
    LaunchedEffect(content) {
        val pages = content?.pages ?: return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone) {
            initialScrollDone = true
            val target = viewModel.initialPage.coerceIn(0, pages.lastIndex)
            if (target > 0) listState.scrollToItem(target)
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.recordPage(it) }
    }
    DisposableEffect(listState) {
        onDispose { viewModel.recordPage(listState.firstVisibleItemIndex) }
    }

    Box(modifier.fillMaxSize()) {
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
                } else {
                    ReaderPages(
                        content = current.content,
                        listState = listState,
                        pageSizes = pageSizes,
                        fallbackRatio = fallbackRatio,
                        imageWidthPx = imageWidthPx,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { controlsVisible = !controlsVisible }
                                }
                                .hazeSource(hazeState),
                    )
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
            ReaderBottomBar(
                page = currentPage,
                total = content?.pages?.size ?: 0,
                hazeState = hazeState,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
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
                    .height(56.dp)
                    .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    total: Int,
    hazeState: HazeState,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.reader_page_of, page + 1, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderPages(
    content: ChapterContent,
    listState: LazyListState,
    pageSizes: SnapshotStateMap<String, IntSize>,
    fallbackRatio: Float,
    imageWidthPx: Int,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(
            items = content.pages,
            key = { _, page -> page.url },
            contentType = { _, _ -> "page" },
        ) { index, page ->
            ReaderPageImage(
                page = page,
                index = index,
                knownSize = pageSizes[page.url],
                fallbackRatio = fallbackRatio,
                imageWidthPx = imageWidthPx,
                onSize = { size -> pageSizes[page.url] = size },
            )
        }
    }
}

@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    index: Int,
    knownSize: IntSize?,
    fallbackRatio: Float,
    imageWidthPx: Int,
    onSize: (IntSize) -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val request =
        remember(page.url, page.splitCount, imageWidthPx, attempt) {
            val url = if (attempt == 0) page.url else page.url.withRetry(attempt)
            ImageRequest.Builder(context)
                .data(url)
                .apply {
                    if (page.splitCount > 0) {
                        // Scrambled pages need their exact original pixel rows, so they cannot be
                        // downsampled before the rows are reordered. The transformation itself
                        // caps the output height.
                        size(Size.ORIGINAL)
                        transformations(ScrambleTransformation(page.splitCount))
                    } else {
                        size(Dimension.Pixels(imageWidthPx), Dimension.Undefined)
                    }
                }
                .crossfade(false)
                .build()
        }
    val ratio =
        if (knownSize != null && knownSize.width > 0 && knownSize.height > 0) {
            knownSize.width.toFloat() / knownSize.height
        } else {
            fallbackRatio
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.reader_page, index + 1),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { success ->
                failed = false
                val drawable = success.result.drawable
                val width = drawable.intrinsicWidth
                val height = drawable.intrinsicHeight
                if (width > 0 && height > 0) {
                    onSize(IntSize(width, height))
                }
            },
            onError = { failed = true },
        )
        if (failed) {
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
                        failed = false
                        attempt += 1
                    },
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

private fun medianPageRatio(sizes: Collection<IntSize>): Float {
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

private fun String.withRetry(attempt: Int): String {
    val separator = if ('?' in this) '&' else '?'
    return "$this${separator}retry=$attempt"
}

private const val DEFAULT_PAGE_RATIO = 3f / 4f
private const val MIN_RATIO_SAMPLES = 3
