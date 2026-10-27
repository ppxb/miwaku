package com.ppxb.miwaku.ui.screen.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.settings.ThemeMode
import com.ppxb.miwaku.ui.component.AmbientGlow
import com.ppxb.miwaku.ui.component.SettingGroup
import com.ppxb.miwaku.ui.component.SettingItem
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showPrefetchDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { AppGraph.readerCacheSizeBytes() }
    }

    Box(modifier.fillMaxSize()) {
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
                                -size.height
                            }
                    },
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopAppBar(
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item {
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
                item {
                    SettingGroup(title = stringResource(R.string.settings_section_reader)) {
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
                    }
                }
                item {
                    SettingGroup(title = stringResource(R.string.settings_section_storage)) {
                        val cacheLabel =
                            cacheSize?.let { Formatter.formatShortFileSize(context, it) }
                                ?: stringResource(R.string.settings_reader_cache_loading)
                        SettingItem(
                            title = stringResource(R.string.settings_reader_cache),
                            subtitle = stringResource(R.string.settings_reader_cache_summary, cacheLabel),
                            onClick = { showClearCacheDialog = true },
                        )
                    }
                }
                item {
                    SettingGroup(title = stringResource(R.string.settings_section_debug)) {
                        SettingItem(
                            title = stringResource(R.string.settings_placeholder_covers),
                            subtitle = stringResource(R.string.settings_placeholder_covers_description),
                            switch = placeholderCovers to onPlaceholderCoversChange,
                        )
                    }
                }
            }
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
}

@StringRes
private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    }

private val PrefetchOptions = listOf(3, 6, 10)

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
