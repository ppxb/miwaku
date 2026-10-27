package com.ppxb.miwaku.ui.screen.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ReadingProgress
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@Composable
internal fun DetailScreen(
    onBack: () -> Unit,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val resume by viewModel.resume.collectAsState()
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val barThreshold = with(density) { 80.dp.toPx() }
    val showBar by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > barThreshold
        }
    }
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val title = (state as? DetailUiState.Content)?.detail?.title.orEmpty()

    Box(modifier.fillMaxSize()) {
        when (val current = state) {
            DetailUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is DetailUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )

            is DetailUiState.Content ->
                DetailContent(
                    detail = current.detail,
                    resume = resume,
                    onOpenChapter = onOpenChapter,
                    onBack = onBack,
                    placeholderCovers = placeholderCovers,
                    listState = listState,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                )
        }
        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            PinnedDetailTopBar(
                title = title,
                hazeState = hazeState,
                onBack = onBack,
            )
        }
        AnimatedVisibility(
            visible = showBackToTop,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.detail_back_to_top),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PinnedDetailTopBar(
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
                modifier =
                    Modifier
                        .weight(1f)
                        .basicMarquee(),
            )
        }
    }
}

@Composable
private fun DetailContent(
    detail: ComicDetail,
    resume: ReadingProgress?,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
    onBack: () -> Unit,
    placeholderCovers: Boolean,
    listState: LazyListState,
    modifier: Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        item {
            PlaylistHeader(
                detail = detail,
                resume = resume,
                onBack = onBack,
                placeholderCovers = placeholderCovers,
                onOpenChapter = onOpenChapter,
            )
        }
        if (detail.description.isNotBlank()) {
            item {
                DescriptionBlock(description = detail.description)
            }
        }
        item {
            ChapterHeader(count = detail.chapters.size)
        }
        if (detail.chapters.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.detail_no_chapters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            items(detail.chapters, key = { it.id }) { chapter ->
                ChapterTrack(
                    chapter = chapter,
                    onClick = { onOpenChapter(detail.id, chapter.id, 0) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    detail: ComicDetail,
    resume: ReadingProgress?,
    onBack: () -> Unit,
    placeholderCovers: Boolean,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val height = with(density) { (windowInfo.containerSize.height / 2).toDp() }
    Box(Modifier.fillMaxWidth().height(height)) {
        if (placeholderCovers) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        } else {
            AsyncImage(
                model = detail.coverUrl,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.15f),
                            0.72f to Color.Black.copy(alpha = 0.5f),
                            0.9f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
        )
        DetailBackButton(
            onBack = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (detail.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail.author,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (detail.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(detail = detail, resume = resume, onOpenChapter = onOpenChapter)
                DownloadButton()
            }
        }
    }
}

@Composable
private fun PlayButton(
    detail: ComicDetail,
    resume: ReadingProgress?,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
) {
    val resumeChapter = resume?.let { progress -> detail.chapters.firstOrNull { it.id == progress.chapterId } }
    val target = resumeChapter ?: detail.chapters.firstOrNull()
    val page = if (resumeChapter != null) resume.page else 0
    val label = if (resumeChapter != null) R.string.detail_continue else R.string.detail_play
    Surface(
        onClick = { target?.let { onOpenChapter(detail.id, it.id, page) } },
        enabled = target != null,
        shape = CircleShape,
        color = Color.White,
        contentColor = Color.Black,
        modifier = Modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DownloadButton() {
    Surface(
        onClick = {},
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.16f),
        contentColor = Color.White,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.detail_download),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DescriptionBlock(description: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (description.length > 100) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            if (expanded) {
                                R.string.detail_description_less
                            } else {
                                R.string.detail_description_more
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun ChapterHeader(count: Int) {
    Text(
        text = stringResource(R.string.detail_chapter_count, count),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChapterTrack(
    chapter: ChapterSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
