package com.ppxb.miwaku.ui.screen.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
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
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
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
        val target = viewModel.initialPage.coerceIn(0, pages.lastIndex)
        if (target > 0) listState.scrollToItem(target)
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
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(content.pages) { index, page ->
            ReaderPageImage(page = page, index = index)
        }
    }
}

@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    index: Int,
) {
    val context = LocalContext.current
    val request =
        remember(page.url, page.splitCount) {
            ImageRequest.Builder(context)
                .data(page.url)
                .size(Size.ORIGINAL)
                .apply {
                    if (page.splitCount > 0) {
                        transformations(ScrambleTransformation(page.splitCount))
                    }
                }
                .crossfade(true)
                .build()
        }
    val painter = rememberAsyncImagePainter(request)
    val source = painter.intrinsicSize
    val ratio =
        if (source.width.isFinite() && source.height.isFinite() && source.width > 0f && source.height > 0f) {
            source.width / source.height
        } else {
            3f / 4f
        }

    Image(
        painter = painter,
        contentDescription = stringResource(R.string.reader_page, index + 1),
        contentScale = ContentScale.FillWidth,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}
