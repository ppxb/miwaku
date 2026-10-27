package com.ppxb.miwaku.ui.screen.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.ui.component.ComicGrid
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState

@Composable
internal fun SearchScreen(
    onOpenComic: (ComicSummary) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The search button only exists to land here, so the field takes focus right away.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit = {
        keyboard?.hide()
        viewModel.search(query)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        SearchField(
            query = query,
            onQueryChange = { query = it },
            onClear = {
                query = ""
                viewModel.reset()
            },
            onSearch = submit,
            focusRequester = focusRequester,
        )
        when (val current = state) {
            SearchUiState.Idle ->
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_hint_title),
                    description = stringResource(R.string.search_hint_description),
                    modifier = Modifier.weight(1f),
                )

            SearchUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is SearchUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = submit,
                    modifier = Modifier.weight(1f),
                )

            is SearchUiState.Content ->
                if (current.comics.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.search_no_results_title),
                        description = stringResource(R.string.search_no_results_description),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.search_results_count, current.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    ComicGrid(
                        comics = current.comics,
                        modifier = Modifier.weight(1f),
                        onComicClick = onOpenComic,
                        placeholderCovers = placeholderCovers,
                    )
                }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onSearch,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.action_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
