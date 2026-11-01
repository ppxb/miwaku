package com.ppxb.miwaku.ui.screen.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.NavPageScaffold

@Composable
fun FavoritesScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
) {
    val favorites by AppGraph.favorites.entries.collectAsState()
    val gridState = rememberLazyGridState()

    NavPageScaffold(
        title = stringResource(R.string.favorites_title),
        subtitle = stringResource(R.string.favorites_subtitle),
        scrollState = gridState,
        modifier = modifier,
    ) { header ->
        if (favorites.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                header()
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = stringResource(R.string.favorites_empty_title),
                    description = stringResource(R.string.favorites_empty_description),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            ComicGrid(
                comics =
                    favorites.map { favorite ->
                        ComicSummary(
                            id = favorite.id,
                            title = favorite.title,
                            author = favorite.author,
                            coverUrl = favorite.coverUrl,
                        )
                    },
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                onComicClick = onOpenComic,
                placeholderCovers = placeholderCovers,
                state = gridState,
                header = header,
            )
        }
    }
}
