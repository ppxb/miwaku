package com.ppxb.miwaku.ui.screen.favorites

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ppxb.miwaku.R
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.PageScaffold

@Composable
fun FavoritesScreen(modifier: Modifier = Modifier) {
    PageScaffold(
        title = stringResource(R.string.favorites_title),
        subtitle = stringResource(R.string.favorites_subtitle),
        modifier = modifier,
    ) {
        EmptyState(
            icon = Icons.Rounded.FavoriteBorder,
            title = stringResource(R.string.favorites_empty_title),
            description = stringResource(R.string.favorites_empty_description),
            modifier = Modifier.weight(1f),
        )
    }
}
