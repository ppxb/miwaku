package com.ppxb.miwaku.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState

@Composable
internal fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenComic: (ComicSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val history by viewModel.history.collectAsState()

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        HomeHeader(onOpenSettings = onOpenSettings)
        Text(
            text = stringResource(R.string.home_section_recent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        )
        if (history.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.AutoStories,
                title = stringResource(R.string.home_empty_title),
                description = stringResource(R.string.home_empty_description),
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            )
        } else {
            ComicGrid(
                comics = history,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                onComicClick = onOpenComic,
            )
        }
    }
}

/** The wordmark on the left and the one entry point we have so far on the right. */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
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
