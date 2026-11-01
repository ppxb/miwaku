package com.ppxb.miwaku.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.NavPageScaffold

@Composable
internal fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val history by viewModel.history.collectAsState()
    val gridState = rememberLazyGridState()

    NavPageScaffold(
        title = stringResource(R.string.tab_home),
        subtitle = stringResource(R.string.home_subtitle),
        scrollState = gridState,
        modifier = modifier,
        actions = {
            if (history.isNotEmpty()) {
                IconButton(onClick = viewModel::clearHistory) {
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
    ) { header ->
        if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                header()
                EmptyState(
                    icon = Icons.Rounded.AutoStories,
                    title = stringResource(R.string.home_empty_title),
                    description = stringResource(R.string.home_empty_description),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            ComicGrid(
                comics = history,
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                onComicClick = onOpenComic,
                placeholderCovers = placeholderCovers,
                state = gridState,
                header = header,
            )
        }
    }
}
