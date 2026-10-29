package com.ppxb.miwaku.ui.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Toc
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.core.data.settings.ReaderBackground
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.ui.component.labelRes
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import com.ppxb.miwaku.ui.reader.NextChapterState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.roundToInt

@Composable
internal fun ReaderTopBar(
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
internal fun ReaderSeekBar(
    page: Int,
    total: Int,
    showPageNumber: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Int) -> Unit,
    onSeekFinished: (Int) -> Unit,
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
                // Only the landing page is worth warming; dragging across pages must not queue
                // a download per intermediate page.
                onSeekFinished(target)
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
internal fun ReaderBottomBar(
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
internal fun ReaderPageIndicator(
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
internal fun ChapterTransitionItem(from: String, to: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_chapter_finished, from),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.reader_chapter_up_next),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = to,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
@Composable
internal fun ReaderFooter(
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

internal fun medianPageRatio(sizes: Collection<PageSize>): Float {
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
internal fun ColorScheme.readerColorScheme(background: ReaderBackground): ColorScheme =
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

/** How many times a page is silently re-fetched before the manual retry appears. */
private const val MAX_AUTO_RETRIES = 2

/** Lets the failed request settle before re-fetching, so a flapping connection is not hammered. */
private const val AUTO_RETRY_DELAY_MS = 600L

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
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
internal fun ReaderChapterSheet(
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
