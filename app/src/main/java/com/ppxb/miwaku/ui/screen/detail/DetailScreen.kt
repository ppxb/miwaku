package com.ppxb.miwaku.ui.screen.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.ppxb.miwaku.model.ChapterProgress
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.download.DownloadTask
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val DetailAccent = Color(0xFF7CC7FF)
private val FavoriteRed = Color(0xFFFF5C8A)
private val DetailBackground = Color(0xFF1C1B1F)
private val CoverPlaceholder = Color(0xFF242327)

private val DetailScheme =
    darkColorScheme(
        primary = DetailAccent,
        onPrimary = Color.Black,
        background = DetailBackground,
        onBackground = Color.White,
        surface = Color(0xFF242327),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2D2C31),
        onSurfaceVariant = Color(0xFFB8B8C4),
        error = Color(0xFFFF6B81),
    )

@Composable
internal fun DetailScreen(
    onBack: () -> Unit,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resume by viewModel.resume.collectAsStateWithLifecycle()
    val tasks by viewModel.downloadTasks.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedChapters.collectAsStateWithLifecycle()
    val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val reading by viewModel.chapterProgress.collectAsStateWithLifecycle()
    val operationError by viewModel.operationError.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val context = LocalContext.current
    LaunchedEffect(operationError) { operationError?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() } }
    val density = LocalDensity.current
    val barThreshold = with(density) { 96.dp.toPx() }
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > barThreshold
        }
    }

    val detail = (state as? DetailUiState.Content)?.detail
    val resumeChapter =
        detail?.let { current ->
            resume?.let { progress -> current.chapters.firstOrNull { it.id == progress.chapterId } }
        }
    val resumeTarget = resumeChapter ?: detail?.chapters?.firstOrNull()
    val resumePage = if (resumeChapter != null) -2 else 0

    DarkSystemBars()

    MaterialTheme(colorScheme = DetailScheme) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = DetailBackground,
            contentColor = Color.White,
        ) {
            Box(Modifier.fillMaxSize()) {
                when (val current = state) {
                    DetailUiState.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = DetailAccent)
                        }

                    is DetailUiState.Failed ->
                        DetailError(
                            message = current.message,
                            onRetry = viewModel::load,
                            modifier = Modifier.fillMaxSize(),
                        )

                    is DetailUiState.Content ->
                        DetailContent(
                            detail = current.detail,
                            tasks = tasks,
                            downloaded = downloaded,
                            reading = reading,
                            onMarkRead = viewModel::markRead,
                            listState = listState,
                            hazeState = hazeState,
                            background = DetailBackground,
                            onOpenChapter = onOpenChapter,
                            placeholderCovers = placeholderCovers,
                            onDownloadChapter = viewModel::downloadChapter,
                            modifier = Modifier.fillMaxSize(),
                        )
                }

                DetailTopBar(
                    title = detail?.title.orEmpty(),
                    scrolled = scrolled,
                    background = DetailBackground,
                    hazeState = hazeState,
                    favorite = favorite,
                    downloadEnabled = detail?.chapters?.isNotEmpty() == true,
                    onBack = onBack,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onDownloadRange = viewModel::download,
                    onRefresh = viewModel::refresh,
                    onShare = { detail?.let { shareComic(context, it) } },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                AnimatedVisibility(
                    visible = detail != null && resumeTarget != null,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .navigationBarsPadding(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        onClick = {
                            val current = detail
                            val target = resumeTarget
                            if (current != null && target != null) {
                                onOpenChapter(current.id, target.id, resumePage)
                            }
                        },
                        shape = CircleShape,
                        color = Color.White,
                        contentColor = Color.Black,
                        modifier = Modifier.height(44.dp),
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
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text =
                                    stringResource(
                                        if (resumeChapter != null) R.string.detail_continue else R.string.detail_play,
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    scrolled: Boolean,
    background: Color,
    hazeState: HazeState,
    favorite: Boolean,
    downloadEnabled: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadRange: (DownloadRange) -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = !scrolled,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TopBarRow(
                title = title,
                showTitle = false,
                glass = true,
                hazeState = hazeState,
                favorite = favorite,
                downloadEnabled = downloadEnabled,
                onBack = onBack,
                onToggleFavorite = onToggleFavorite,
                onDownloadRange = onDownloadRange,
                onRefresh = onRefresh,
                onShare = onShare,
            )
        }
        AnimatedVisibility(
            visible = scrolled,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .hazeBlur(HazeInput.Sources(hazeState), detailBarBlurStyle(background))
                        .statusBarsPadding()
                        .height(56.dp),
                )
                TopBarRow(
                    title = title,
                    showTitle = true,
                    glass = false,
                    hazeState = hazeState,
                    favorite = favorite,
                    downloadEnabled = downloadEnabled,
                    onBack = onBack,
                    onToggleFavorite = onToggleFavorite,
                    onDownloadRange = onDownloadRange,
                    onRefresh = onRefresh,
                    onShare = onShare,
                )
            }
        }
    }
}

@Composable
private fun TopBarRow(
    title: String,
    showTitle: Boolean,
    glass: Boolean,
    hazeState: HazeState,
    favorite: Boolean,
    downloadEnabled: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadRange: (DownloadRange) -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            glass = glass,
            hazeState = hazeState,
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (showTitle) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .then(
                        if (glass) {
                            Modifier
                                .hazeBlur(HazeInput.Sources(hazeState), detailGlassStyle())
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(22.dp),
                                )
                        } else {
                            Modifier
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillIcon(
                imageVector = if (favorite) Icons.Filled.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription =
                    stringResource(
                        if (favorite) R.string.detail_unfavorite else R.string.detail_favorite,
                    ),
                tint = if (favorite) FavoriteRed else Color.White,
                onClick = onToggleFavorite,
            )
            DownloadMenuPill(enabled = downloadEnabled, onSelect = onDownloadRange)
            MoreMenuPill(onRefresh = onRefresh, onShare = onShare)
        }
    }
}

@Composable
private fun CircleIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    enabled: Boolean = true,
    glass: Boolean,
    hazeState: HazeState,
) {
    val glassModifier =
        if (glass) {
            Modifier
                .hazeBlur(HazeInput.Sources(hazeState), detailGlassStyle())
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(glassModifier)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PillIcon(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun DownloadMenuPill(
    enabled: Boolean,
    onSelect: (DownloadRange) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PillIcon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(R.string.detail_download),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            onClick = { if (enabled) expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DownloadRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(stringResource(range.labelRes())) },
                    onClick = {
                        expanded = false
                        onSelect(range)
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreMenuPill(
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PillIcon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.detail_more),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_refresh)) },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_share)) },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    detail: ComicDetail,
    tasks: Map<String, DownloadTask>,
    downloaded: Set<String>,
    reading: Map<String, ChapterProgress>,
    onMarkRead: (ChapterSummary, Boolean) -> Unit,
    listState: LazyListState,
    hazeState: HazeState,
    background: Color,
    onOpenChapter: (albumId: String, chapterId: String, page: Int) -> Unit,
    placeholderCovers: Boolean,
    onDownloadChapter: (ChapterSummary) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().hazeSource(hazeState),
    ) {
        item(key = "cover") {
            CoverHeader(
                detail = detail,
                placeholderCovers = placeholderCovers,
                background = background,
            )
        }
        if (detail.tags.isNotEmpty()) {
            item(key = "tags") {
                TagFlow(tags = detail.tags)
            }
        }
        if (detail.description.isNotBlank()) {
            item(key = "description") {
                DetailDescription(description = detail.description, background = background)
            }
        }
        item(key = "chapter-header") {
            ChapterHeader(count = detail.chapters.size)
        }
        itemsIndexed(detail.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
            if (index > 0) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
            ChapterTrack(
                chapter = chapter,
                task = tasks[chapter.id],
                downloaded = downloaded.contains(chapter.id),
                progress = reading[chapter.id],
                onClick = { onOpenChapter(detail.id, chapter.id, -2) },
                onStart = { onOpenChapter(detail.id, chapter.id, 0) },
                onMarkRead = { onMarkRead(chapter, reading[chapter.id]?.isRead != true) },
                onDownload = { onDownloadChapter(chapter) },
            )
            if (index == detail.chapters.lastIndex) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
        item(key = "bottom-space") {
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun CoverHeader(
    detail: ComicDetail,
    placeholderCovers: Boolean,
    background: Color,
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val height = with(density) { (windowInfo.containerSize.height * 0.48f).toDp() }
    val scrimHeight = with(density) { (windowInfo.containerSize.height * 0.35f).toDp() }
    val context = LocalContext.current

    val coverRequest =
        remember(detail.id, detail.coverUrl) {
            ImageRequest
                .Builder(context)
                .data(detail.coverUrl)
                // Stable keys: a refreshed image host must not re-download the same cover.
                .memoryCacheKey("album-cover:${detail.id}")
                .diskCacheKey("album-cover:${detail.id}")
                .crossfade(550)
                .build()
        }

    Box(Modifier.fillMaxWidth().height(height)) {
        if (placeholderCovers) {
            Box(Modifier.fillMaxSize().background(CoverPlaceholder))
        } else {
            AsyncImage(
                model = coverRequest,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(CoverPlaceholder),
            )
        }
        // Keeps the white action icons legible over a bright cover.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    smoothScrimBrush(
                        from = Color.Black.copy(alpha = 0.5f),
                        to = Color.Black.copy(alpha = 0f),
                    ),
                ),
        )
        // SimpMusic's artwork scrim: 35% of the screen, smoothstep, ending in the page colour.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(scrimHeight)
                .background(
                    smoothScrimBrush(
                        from = background.copy(alpha = 0f),
                        to = background,
                    ),
                ),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = detail.title,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        lineBreak = LineBreak.Heading,
                    ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.author.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatAuthors(detail.author),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = detail.id,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xC4FFFFFF),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(tags: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White.copy(alpha = 0.82f),
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Komikku-style summary: three lines, bottom fade and a rotating caret; tap anywhere toggles. */
@Composable
private fun DetailDescription(
    description: String,
    background: Color,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "descriptionArrow",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column(Modifier.animateContentSize()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, background),
                    ),
                ),
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription =
                stringResource(
                    if (expanded) R.string.detail_description_less else R.string.detail_description_more,
                ),
            tint = DetailAccent,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(20.dp)
                    .rotate(arrowRotation),
        )
    }
}

@Composable
private fun ChapterHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.detail_chapter_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun ChapterTrack(
    chapter: ChapterSummary,
    task: DownloadTask?,
    downloaded: Boolean,
    progress: ChapterProgress?,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onMarkRead: () -> Unit,
    onDownload: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chapter.name, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                progress?.isRead == true -> "已读"
                progress?.hasStarted == true -> "阅读中 · 第 " + ((progress.page ?: 0) + 1) + " 页"
                else -> "未读"
            }, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Box {
            TextButton(onClick = { showActions = true }) { Text("操作") }
            androidx.compose.material3.DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(if (progress?.isRead == true) "标记为未读" else "标记为已读") },
                    onClick = { showActions = false; onMarkRead() })
                androidx.compose.material3.DropdownMenuItem(text = { Text("从头阅读") }, onClick = { showActions = false; onStart() })
            }
        }
        Spacer(Modifier.width(8.dp))
        ChapterDownloadControl(
            task = task,
            downloaded = downloaded,
            onDownload = onDownload,
        )
    }
}

/** Komikku-style 32dp state layer: filled check, progress ring, error glyph, or download arrow. */
@Composable
private fun ChapterDownloadControl(
    task: DownloadTask?,
    downloaded: Boolean,
    onDownload: () -> Unit,
) {
    val slot = Modifier.size(32.dp)
    when {
        downloaded || task?.state == DownloadState.Completed ->
            Box(slot, contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.detail_downloaded),
                    tint = DetailAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
        task?.state == DownloadState.Running || task?.state == DownloadState.Queued ->
            Box(slot, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = DetailAccent,
                )
            }
        task?.state == DownloadState.Failed ->
            Box(
                modifier = slot.clip(CircleShape).clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.action_retry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        else ->
            Box(
                modifier = slot.clip(CircleShape).clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.detail_download),
                    tint = DetailAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
    }
}

@Composable
private fun DetailError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry), color = DetailAccent)
        }
    }
}

@StringRes
private fun DownloadRange.labelRes(): Int =
    when (this) {
        DownloadRange.All -> R.string.detail_download_all
        DownloadRange.Next1 -> R.string.detail_download_next_1
        DownloadRange.Next5 -> R.string.detail_download_next_5
        DownloadRange.Next10 -> R.string.detail_download_next_10
        DownloadRange.Next25 -> R.string.detail_download_next_25
        DownloadRange.Unread -> R.string.detail_download_unread
    }

private val AuthorSeparators = Regex("[,，、;；/&＆]")

/** JM returns multiple authors separated by commas or slashes; normalise to "A / B". */
private fun formatAuthors(raw: String): String =
    raw.split(AuthorSeparators)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" / ")

@Composable
private fun detailBarBlurStyle(tint: Color): HazeBlurStyle =
    HazeBlurStyle {
        blurRadius(24.dp)
        backgroundColor(tint)
        colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = 0.55f))))
        fallbackColorEffect(HazeColorEffect.tint(tint))
    }

@Composable
private fun detailGlassStyle(): HazeBlurStyle =
    HazeBlurStyle {
        blurRadius(20.dp)
        backgroundColor(Color.Transparent)
        colorEffects(listOf(HazeColorEffect.tint(Color.White.copy(alpha = 0.06f))))
        fallbackColorEffect(HazeColorEffect.tint(Color.Black.copy(alpha = 0.45f)))
    }

/**
 * SimpMusic's scrim curve: smoothstep stops, interpolated here so the fade has no visible edge.
 * Adapted from SimpMusic (Apache-2.0), modified.
 */
private fun smoothScrimBrush(
    from: Color,
    to: Color,
    steps: Int = 24,
): Brush =
    Brush.verticalGradient(
        colorStops =
            Array(steps + 1) { index ->
                val t = index / steps.toFloat()
                t to lerp(from, to, t * t * (3f - 2f * t))
            },
    )

/** The page is always dark, so the status/navigation icon contrast must be forced too. */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun shareComic(context: Context, detail: ComicDetail) {
    val text = "${detail.title}\nJM ${detail.id}"
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null))
    }
}
