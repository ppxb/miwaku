package com.ppxb.miwaku.ui.screen.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
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
import com.ppxb.miwaku.ui.component.ComicGridSkeleton
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.NavPageScaffold

@Composable
internal fun CategoryScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()

    NavPageScaffold(
        title = stringResource(R.string.category_title),
        subtitle = stringResource(R.string.category_subtitle),
        scrollState = gridState,
        modifier = modifier,
        headerExtra = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RankingOrder.entries.forEach { order ->
                    FilterChip(
                        selected = viewModel.order == order,
                        onClick = { viewModel.select(order) },
                        label = {
                            Text(
                                text =
                                    stringResource(
                                        when (order) {
                                            RankingOrder.Latest -> R.string.category_order_latest
                                            RankingOrder.Popular -> R.string.category_order_popular
                                        },
                                    ),
                            )
                        },
                    )
                }
            }
        },
    ) { header ->
        when (val current = state) {
            CategoryUiState.Loading ->
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    header()
                    ComicGridSkeleton(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 12.dp),
                    )
                }

            is CategoryUiState.Failed ->
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    header()
                    ErrorState(
                        message = current.message,
                        onRetry = viewModel::load,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

            is CategoryUiState.Content ->
                if (current.comics.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        header()
                        EmptyState(
                            icon = Icons.Rounded.Category,
                            title = stringResource(R.string.category_empty_title),
                            description = stringResource(R.string.category_empty_description),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                } else {
                    ComicGrid(
                        comics = current.comics,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        onComicClick = onOpenComic,
                        placeholderCovers = placeholderCovers,
                        state = gridState,
                        header = header,
                    )
                }
        }
    }
}
