package com.ppxb.miwaku.ui.screen.explore

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ppxb.miwaku.R
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.PageScaffold

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    PageScaffold(
        title = stringResource(R.string.explore_title),
        subtitle = stringResource(R.string.explore_subtitle),
        modifier = modifier,
    ) {
        EmptyState(
            icon = Icons.Rounded.Explore,
            title = stringResource(R.string.explore_empty_title),
            description = stringResource(R.string.explore_empty_description),
            modifier = Modifier.weight(1f),
        )
    }
}
