package com.ppxb.miwaku.ui.screen.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material3.MaterialTheme
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
import com.ppxb.miwaku.ui.component.AmbientGlow
import com.ppxb.miwaku.ui.component.ChipRow
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.ComicGridSkeleton
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.NavPageHeader
import com.ppxb.miwaku.ui.component.navBarBottomInset
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
internal fun CategoryScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    val hazeState = rememberHazeState()
    val bottomInset = navBarBottomInset()
    val categories = RankingCategory.entries
    val orders = RankingOrder.entries
    val categoryLabels = ArrayList<String>(categories.size)
    for (category in categories) categoryLabels += stringResource(category.label)
    val orderLabels = ArrayList<String>(orders.size)
    for (order in orders) orderLabels += stringResource(order.label)

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            AmbientGlow(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Column(Modifier.fillMaxSize()) {
                // Same head as explore: the filters belong to the heading, so they stay put while
                // the grid scrolls underneath.
                NavPageHeader(
                    title = stringResource(R.string.category_title),
                    subtitle = stringResource(R.string.category_subtitle),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    extra = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipRow(
                                labels = categoryLabels,
                                selectedIndex = categories.indexOf(viewModel.category),
                                onSelect = { viewModel.selectCategory(categories[it]) },
                            )
                            ChipRow(
                                labels = orderLabels,
                                selectedIndex = orders.indexOf(viewModel.order),
                                onSelect = { viewModel.selectOrder(orders[it]) },
                            )
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset)) {
                    when (val current = state) {
                        CategoryUiState.Loading ->
                            ComicGridSkeleton(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                contentPadding = PaddingValues(top = 8.dp),
                            )

                        is CategoryUiState.Failed ->
                            ErrorState(
                                message = current.message,
                                onRetry = viewModel::load,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            )

                        is CategoryUiState.Content ->
                            if (current.comics.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Rounded.Category,
                                    title = stringResource(R.string.category_empty_title),
                                    description = stringResource(R.string.category_empty_description),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                )
                            } else {
                                ComicGrid(
                                    comics = current.comics,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                    onComicClick = onOpenComic,
                                    placeholderCovers = placeholderCovers,
                                    state = gridState,
                                )
                            }
                    }
                }
            }
        }
    }
}
