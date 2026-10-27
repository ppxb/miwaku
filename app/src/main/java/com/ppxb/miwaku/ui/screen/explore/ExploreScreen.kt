package com.ppxb.miwaku.ui.screen.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ExploreSection
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState
import com.ppxb.miwaku.ui.component.topBarBlurStyle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
internal fun ExploreScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val hazeState = rememberHazeState()
    var topBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier.fillMaxSize()) {
        when (val current = state) {
            ExploreUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = topBarHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is ExploreUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = viewModel::load,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = topBarHeight, start = 20.dp, end = 20.dp),
                )

            is ExploreUiState.Content -> {
                val section = current.sections.getOrNull(current.selected)
                if (section == null || section.comics.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Explore,
                        title = stringResource(R.string.explore_empty_title),
                        description = stringResource(R.string.explore_empty_description),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(top = topBarHeight, start = 20.dp, end = 20.dp),
                    )
                } else {
                    ComicGrid(
                        comics = section.comics,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                                .hazeSource(hazeState),
                        onComicClick = onOpenComic,
                        placeholderCovers = placeholderCovers,
                        contentPadding = PaddingValues(top = topBarHeight),
                    )
                }
            }
        }

        ExploreHeader(
            sections = (state as? ExploreUiState.Content)?.sections.orEmpty(),
            selected = (state as? ExploreUiState.Content)?.selected ?: 0,
            onSelect = viewModel::select,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreHeader(
    sections: List<ExploreSection>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.statusBarsPadding()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.explore_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0),
        )
        if (sections.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 15.dp),
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
