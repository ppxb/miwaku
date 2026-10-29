package com.ppxb.miwaku.ui.screen.favorites

import androidx.compose.foundation.layout.PaddingValues
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
import com.ppxb.miwaku.ui.component.PageScaffold

@Composable
fun FavoritesScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
) {
    val favorites by AppGraph.favorites.entries.collectAsState()
    PageScaffold(
        title = stringResource(R.string.favorites_title),
        subtitle = stringResource(R.string.favorites_subtitle),
        modifier = modifier,
    ) {
        if (favorites.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.FavoriteBorder,
                title = stringResource(R.string.favorites_empty_title),
                description = stringResource(R.string.favorites_empty_description),
                modifier = Modifier.weight(1f),
            )
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
                onComicClick = onOpenComic,
                placeholderCovers = placeholderCovers,
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
