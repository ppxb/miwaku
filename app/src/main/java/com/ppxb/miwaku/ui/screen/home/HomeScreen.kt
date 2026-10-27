package com.ppxb.miwaku.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
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
    var topBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = topBarHeight),
            ) {
                RecentHeader(history = history, onClear = viewModel::clearHistory)
                EmptyState(
                    icon = Icons.Rounded.AutoStories,
                    title = stringResource(R.string.home_empty_title),
                    description = stringResource(R.string.home_empty_description),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ComicGrid(
                comics = history,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .hazeSource(hazeState),
                onComicClick = onOpenComic,
                placeholderCovers = placeholderCovers,
                contentPadding = PaddingValues(top = topBarHeight),
                header = {
                    RecentHeader(history = history, onClear = viewModel::clearHistory)
                },
            )
        }
        HomeHeader(
            onOpenSettings = onOpenSettings,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                    .onGloballyPositioned { coordinates ->
                        topBarHeight = with(density) { coordinates.size.height.toDp() }
                    },
        )
    }
}

@Composable
private fun RecentHeader(
    history: List<ComicSummary>,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_section_recent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (history.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.home_clear_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The wordmark on the left and the one entry point we have so far on the right. */
@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.action_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
