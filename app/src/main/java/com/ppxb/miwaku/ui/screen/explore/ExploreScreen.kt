package com.ppxb.miwaku.ui.screen.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.CircularProgressIndicator
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
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.PageScaffold

@Composable
internal fun ExploreScreen(
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    PageScaffold(
        title = stringResource(R.string.explore_title),
        subtitle = stringResource(R.string.explore_subtitle),
        modifier = modifier,
    ) {
        when (val current = state) {
            ExploreUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is ExploreUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.weight(1f),
                )

            is ExploreUiState.Content -> {
                Text(
                    text = stringResource(R.string.explore_section_latest),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                )
                if (current.comics.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Explore,
                        title = stringResource(R.string.explore_empty_title),
                        description = stringResource(R.string.explore_empty_description),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ComicGrid(
                        comics = current.comics,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
