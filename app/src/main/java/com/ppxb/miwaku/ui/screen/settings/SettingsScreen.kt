package com.ppxb.miwaku.ui.screen.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.NetworkStats
import com.ppxb.miwaku.core.data.settings.ThemeMode
import com.ppxb.miwaku.ui.component.AmbientGlow
import com.ppxb.miwaku.ui.component.SettingGroup
import com.ppxb.miwaku.ui.component.SettingItem
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    placeholderCovers: Boolean,
    onPlaceholderCoversChange: (Boolean) -> Unit,
    prefetchAhead: Int,
    onPrefetchAheadChange: (Int) -> Unit,
    wifiOnlyPrefetch: Boolean,
    onWifiOnlyPrefetchChange: (Boolean) -> Unit,
    lowMemory: Boolean,
    onLowMemoryChange: (Boolean) -> Unit,
    cacheLimitMb: Int,
    onCacheLimitMbChange: (Int) -> Unit,
    downloadConcurrency: Int,
    onDownloadConcurrencyChange: (Int) -> Unit,
    wifiOnlyDownloads: Boolean,
    onWifiOnlyDownloadsChange: (Boolean) -> Unit,
    downloadLimitMb: Int,
    onDownloadLimitChange: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showPrefetchDialog by rememberSaveable { mutableStateOf(false) }
    var showCacheLimitDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showDownloadConcurrencyDialog by rememberSaveable { mutableStateOf(false) }
    var showDownloadLimitDialog by rememberSaveable { mutableStateOf(false) }
    var showNetworkStatsDialog by rememberSaveable { mutableStateOf(false) }
    var networkStats by remember { mutableStateOf<NetworkStats?>(null) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var downloadSize by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val barThreshold = with(density) { 64.dp.toPx() }
    var headerItemHeightPx by remember { mutableIntStateOf(0) }
    val showBar by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > barThreshold
        }
    }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { AppGraph.readerCacheSizeBytes() }
    }
    // Recompute when a completed/failed task is added, removed, or replaced; not on page updates.
    val downloadTasks by AppGraph.downloadManager.tasks.collectAsState()
    val finishedDownloads =
        downloadTasks
            .filter {
                it.state == DownloadState.Completed || it.state == DownloadState.Failed
            }.map { it.chapterId }
    LaunchedEffect(finishedDownloads) {
        downloadSize = withContext(Dispatchers.IO) { AppGraph.downloadStore.downloadSizeBytes() }
    }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            // The theme's primary washed across the top; it leaves with the list instead of staying pinned.
            AmbientGlow(
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY =
                                if (listState.firstVisibleItemIndex == 0) {
                                    -listState.firstVisibleItemScrollOffset.toFloat()
                                } else {
                                    -headerItemHeightPx.toFloat()
                                }
                        },
            )
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "settings-header") {
                    // The header and the first groups share one item: the ambient glow tracks the
                    // item's scroll offset, so the item must outlast the glow or the background
                    // would jump to the page colour the moment the header leaves.
                    Column(Modifier.onSizeChanged { headerItemHeightPx = it.height }) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(start = 4.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SettingGroup(title = stringResource(R.string.settings_section_style)) {
                                SettingItem(
                                    title = stringResource(R.string.settings_theme),
                                    subtitle = stringResource(themeMode.labelRes()),
                                    onClick = { showThemeDialog = true },
                                )
                                SettingItem(
                                    title = stringResource(R.string.settings_liquid_glass),
                                    subtitle = stringResource(R.string.settings_liquid_glass_description),
                                    switch = liquidGlass to onLiquidGlassChange,
                                )
                            }
                        }
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SettingGroup(title = stringResource(R.string.settings_section_reader_loading)) {
                                SettingItem(
                                    title = stringResource(R.string.settings_prefetch_pages),
                                    subtitle = stringResource(R.string.settings_prefetch_pages_summary, prefetchAhead),
                                    onClick = { showPrefetchDialog = true },
                                )
                                SettingItem(
                                    title = stringResource(R.string.settings_wifi_only_prefetch),
                                    subtitle = stringResource(R.string.settings_wifi_only_prefetch_description),
                                    switch = wifiOnlyPrefetch to onWifiOnlyPrefetchChange,
                                )
                                SettingItem(
                                    title = stringResource(R.string.settings_low_memory),
                                    subtitle = stringResource(R.string.settings_low_memory_description),
                                    switch =
                                        lowMemory to { enabled ->
                                            onLowMemoryChange(enabled)
                                            scope.launch {
                                                withContext(Dispatchers.IO) { AppGraph.pageStore.trimToLimit() }
                                                cacheSize =
                                                    withContext(Dispatchers.IO) { AppGraph.readerCacheSizeBytes() }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SettingGroup(title = stringResource(R.string.settings_section_downloads)) {
                            SettingItem(
                                title = stringResource(R.string.settings_download_queue),
                                subtitle = stringResource(R.string.settings_download_queue_summary),
                                onClick = onOpenDownloads,
                            )
                            SettingItem(
                                title = stringResource(R.string.settings_download_concurrency),
                                subtitle =
                                    stringResource(
                                        R.string.settings_download_concurrency_summary,
                                        downloadConcurrency,
                                    ),
                                onClick = { showDownloadConcurrencyDialog = true },
                            )
                            SettingItem(
                                title = stringResource(R.string.settings_wifi_only_downloads),
                                subtitle = stringResource(R.string.settings_wifi_only_downloads_description),
                                switch = wifiOnlyDownloads to onWifiOnlyDownloadsChange,
                            )
                            val downloadUsage =
                                downloadSize?.let { Formatter.formatShortFileSize(context, it) }
                                    ?: stringResource(R.string.settings_download_usage_loading)
                            val downloadLimitSubtitle =
                                if (downloadLimitMb <= 0) {
                                    stringResource(R.string.settings_download_limit_no_limit, downloadUsage)
                                } else {
                                    stringResource(
                                        R.string.settings_download_limit_summary,
                                        downloadUsage,
                                        cacheLimitLabel(downloadLimitMb),
                                    )
                                }
                            SettingItem(
                                title = stringResource(R.string.settings_download_limit),
                                subtitle = downloadLimitSubtitle,
                                onClick = { showDownloadLimitDialog = true },
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SettingGroup(title = stringResource(R.string.settings_section_storage)) {
                            val cacheLabel =
                                cacheSize?.let { Formatter.formatShortFileSize(context, it) }
                                    ?: stringResource(R.string.settings_reader_cache_loading)
                            SettingItem(
                                title = stringResource(R.string.settings_reader_cache),
                                subtitle = stringResource(R.string.settings_reader_cache_summary, cacheLabel),
                                onClick = { showClearCacheDialog = true },
                            )
                            val effectiveLimitMb =
                                if (lowMemory) {
                                    minOf(cacheLimitMb, LOW_MEMORY_CACHE_LIMIT_MB)
                                } else {
                                    cacheLimitMb
                                }
                            val limitLabel = cacheLimitLabel(effectiveLimitMb)
                            SettingItem(
                                title = stringResource(R.string.settings_cache_limit),
                                subtitle = stringResource(R.string.settings_cache_limit_summary, limitLabel),
                                onClick = { showCacheLimitDialog = true },
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SettingGroup(title = stringResource(R.string.settings_section_debug)) {
                            SettingItem(
                                title = stringResource(R.string.settings_placeholder_covers),
                                subtitle = stringResource(R.string.settings_placeholder_covers_description),
                                switch = placeholderCovers to onPlaceholderCoversChange,
                            )
                            val metrics = AppGraph.networkMetrics()
                            SettingItem(
                                title = stringResource(R.string.settings_network_stats),
                                subtitle =
                                    stringResource(
                                        R.string.settings_network_stats_summary,
                                        metrics.requests,
                                        metrics.failures,
                                        metrics.retries,
                                    ),
                                onClick = {
                                    networkStats = metrics
                                    showNetworkStatsDialog = true
                                },
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            TopAppBar(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                        .statusBarsPadding(),
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                // The NavHost already starts below the status bar.
                windowInsets = WindowInsets(0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
            )
        }
    }

    if (showThemeDialog) {
        ThemeDialog(
            current = themeMode,
            onSelect = {
                onThemeModeChange(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showPrefetchDialog) {
        PrefetchDialog(
            current = prefetchAhead,
            onSelect = {
                onPrefetchAheadChange(it)
                showPrefetchDialog = false
            },
            onDismiss = { showPrefetchDialog = false },
        )
    }
    if (showCacheLimitDialog) {
        CacheLimitDialog(
            current = cacheLimitMb,
            onSelect = { mb ->
                onCacheLimitMbChange(mb)
                showCacheLimitDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { AppGraph.pageStore.trimToLimit() }
                    cacheSize = withContext(Dispatchers.IO) { AppGraph.readerCacheSizeBytes() }
                }
            },
            onDismiss = { showCacheLimitDialog = false },
        )
    }
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.settings_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) { AppGraph.clearReaderCache() }
                            cacheSize = withContext(Dispatchers.IO) { AppGraph.readerCacheSizeBytes() }
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showDownloadConcurrencyDialog) {
        DownloadConcurrencyDialog(
            current = downloadConcurrency,
            onSelect = {
                onDownloadConcurrencyChange(it)
                showDownloadConcurrencyDialog = false
            },
            onDismiss = { showDownloadConcurrencyDialog = false },
        )
    }
    if (showDownloadLimitDialog) {
        DownloadLimitDialog(
            current = downloadLimitMb,
            onSelect = { mb ->
                onDownloadLimitChange(mb)
                showDownloadLimitDialog = false
                AppGraph.downloadManager.trimToStorageLimit()
            },
            onDismiss = { showDownloadLimitDialog = false },
        )
    }
    if (showNetworkStatsDialog) {
        NetworkStatsDialog(
            stats = networkStats ?: AppGraph.networkMetrics(),
            onDismiss = { showNetworkStatsDialog = false },
        )
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    }

private val PrefetchOptions = listOf(3, 6, 10)
private val CacheLimitOptions = listOf(512, 1024, 2048)
private val DownloadConcurrencyOptions = listOf(1, 2, 3, 5)
private val DownloadLimitOptions = listOf(0, 2048, 4096, 8192, 16384)
private const val LOW_MEMORY_CACHE_LIMIT_MB = 256

private fun cacheLimitLabel(mb: Int): String =
    if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

@Composable
private fun PrefetchDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_prefetch_pages)) },
        text = {
            Column {
                PrefetchOptions.forEach { pages ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = pages == current,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(pages) },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pages == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_prefetch_pages_option, pages),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CacheLimitDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cache_limit)) },
        text = {
            Column {
                CacheLimitOptions.forEach { mb ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = mb == current,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(mb) },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mb == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = cacheLimitLabel(mb),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadConcurrencyDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_download_concurrency)) },
        text = {
            Column {
                DownloadConcurrencyOptions.forEach { value ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = value == current,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(value) },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_download_concurrency_option, value),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadLimitDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_download_limit)) },
        text = {
            Column {
                DownloadLimitOptions.forEach { mb ->
                    val label =
                        if (mb <= 0) {
                            stringResource(R.string.settings_download_limit_unlimited)
                        } else {
                            cacheLimitLabel(mb)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = mb == current,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(mb) },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mb == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun NetworkStatsDialog(
    stats: NetworkStats,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_network_stats)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_network_stats_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                StatRow(stringResource(R.string.settings_network_requests), stats.requests.toString())
                StatRow(stringResource(R.string.settings_network_failures), stats.failures.toString())
                StatRow(stringResource(R.string.settings_network_retries), stats.retries.toString())
                StatRow(stringResource(R.string.settings_network_failovers), stats.failovers.toString())
                StatRow(stringResource(R.string.settings_network_throttled), stats.throttled.toString())
                StatRow(stringResource(R.string.settings_network_average), "${stats.averageMs} ms")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = mode == current,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(mode) },
                                ).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(mode.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
