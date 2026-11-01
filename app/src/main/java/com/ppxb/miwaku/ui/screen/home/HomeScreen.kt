package com.ppxb.miwaku.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.ui.component.AmbientGlow
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
internal fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val history by viewModel.history.collectAsState()
    val hazeState = rememberHazeState()
    val gridState = rememberLazyGridState()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    // The floating navigation bar overlays the grid, so the list has to end above it.
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp
    // The heading is the first grid item, so index 0 is still the heading; the compact bar only
    // fades in once it has left, exactly like the settings page.
    val showBar by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            // The glow scrolls away with the list instead of staying pinned under the bar; being
            // part of the haze source, the bar then samples it and blends instead of cutting it.
            AmbientGlow(
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY =
                                if (gridState.firstVisibleItemIndex == 0) {
                                    -gridState.firstVisibleItemScrollOffset.toFloat()
                                } else {
                                    -headerHeightPx.toFloat()
                                }
                        },
            )
            if (history.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    HomeHeader(
                        hasHistory = false,
                        onClear = {},
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    EmptyState(
                        icon = Icons.Rounded.AutoStories,
                        title = stringResource(R.string.home_empty_title),
                        description = stringResource(R.string.home_empty_description),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 20.dp),
                    )
                }
            } else {
                ComicGrid(
                    comics = history,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    onComicClick = onOpenComic,
                    placeholderCovers = placeholderCovers,
                    contentPadding = PaddingValues(bottom = bottomPadding),
                    state = gridState,
                    header = {
                        HomeHeader(
                            hasHistory = true,
                            onClear = viewModel::clearHistory,
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
                        )
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            HomeTopBar(
                hasHistory = history.isNotEmpty(),
                onClear = viewModel::clearHistory,
                onOpenSettings = onOpenSettings,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                        .statusBarsPadding(),
            )
        }
    }
}

/** The page heading, with the history action next to Settings so the grid stays clean. */
@Composable
private fun HomeHeader(
    hasHistory: Boolean,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .statusBarsPadding()
                // The grid already insets its content, so the heading only adds vertical air.
                .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tab_home),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasHistory) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.home_clear_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.action_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact bar that takes over once the heading scrolls away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    hasHistory: Boolean,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.tab_home),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        actions = {
            if (hasHistory) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.home_clear_history),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.action_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        windowInsets = WindowInsets(0),
    )
}
