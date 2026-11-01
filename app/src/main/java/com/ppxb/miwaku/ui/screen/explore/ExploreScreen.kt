package com.ppxb.miwaku.ui.screen.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ExploreSection
import com.ppxb.miwaku.ui.component.AmbientGlow
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.ComicGridSkeleton
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.NavPageHeader
import com.ppxb.miwaku.ui.component.navBarBottomInset
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@Composable
internal fun ExploreScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sections = (state as? ExploreUiState.Content)?.sections.orEmpty()
    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { sections.size })
    val gridStates = remember(sections.size) { List(sections.size) { LazyGridState() } }
    val bottomInset = navBarBottomInset()

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            AmbientGlow(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Column(Modifier.fillMaxSize()) {
                // The heading stays put here: its section chips have to remain reachable while the
                // grids below scroll, which is also why this page has no collapsing bar.
                NavPageHeader(
                    title = stringResource(R.string.explore_title),
                    subtitle = stringResource(R.string.explore_subtitle),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    extra = {
                        if (sections.isNotEmpty()) {
                            ExploreChips(
                                sections = sections,
                                selected = pagerState.currentPage,
                                onSelect = { index ->
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            )
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset)) {
                    when (val current = state) {
                        ExploreUiState.Loading ->
                            ComicGridSkeleton(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                contentPadding = PaddingValues(top = 8.dp),
                            )

                        is ExploreUiState.Failed ->
                            ErrorState(
                                message = current.message,
                                onRetry = viewModel::load,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            )

                        is ExploreUiState.Content ->
                            if (sections.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Rounded.Explore,
                                    title = stringResource(R.string.explore_empty_title),
                                    description = stringResource(R.string.explore_empty_description),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                )
                            } else {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize(),
                                ) { page ->
                                    ComicGrid(
                                        comics = sections[page].comics,
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                        onComicClick = onOpenComic,
                                        placeholderCovers = placeholderCovers,
                                        state = gridStates[page],
                                    )
                                }
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreChips(
    sections: List<ExploreSection>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        itemsIndexed(sections, key = { _, section -> section.id }) { index, section ->
            ExploreChip(
                text = section.title,
                selected = index == selected,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun ExploreChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        border =
            if (selected) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
