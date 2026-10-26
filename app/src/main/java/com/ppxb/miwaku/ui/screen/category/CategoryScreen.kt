package com.ppxb.miwaku.ui.screen.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ppxb.miwaku.R
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.PageScaffold

@Composable
fun CategoryScreen(modifier: Modifier = Modifier) {
    PageScaffold(
        title = stringResource(R.string.category_title),
        subtitle = stringResource(R.string.category_subtitle),
        modifier = modifier,
    ) {
        EmptyState(
            icon = Icons.Rounded.Category,
            title = stringResource(R.string.category_empty_title),
            description = stringResource(R.string.category_empty_description),
            modifier = Modifier.weight(1f),
        )
    }
}
