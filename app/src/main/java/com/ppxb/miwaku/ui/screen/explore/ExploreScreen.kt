package com.ppxb.miwaku.ui.screen.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
                            ChipRow(
                                labels = sections.map { it.title },
                                selectedIndex = pagerState.currentPage,
                                onSelect = { index ->
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                modifier = Modifier.padding(bottom = 8.dp),
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
