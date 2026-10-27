package com.ppxb.miwaku.ui.screen.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ReaderPage
import com.ppxb.miwaku.ui.component.EmptyState
import com.ppxb.miwaku.ui.component.ErrorState

@Composable
internal fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val title =
        when (val current = state) {
            is ReaderUiState.Content -> current.content.title.ifBlank { stringResource(R.string.reader_title) }
            else -> stringResource(R.string.reader_title)
        }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        ReaderTopBar(title = title, onBack = onBack)
        when (val current = state) {
            ReaderUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is ReaderUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.weight(1f),
                )

            is ReaderUiState.Content ->
                if (current.content.pages.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.AutoStories,
                        title = stringResource(R.string.reader_empty_title),
                        description = stringResource(R.string.reader_empty_description),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ReaderPages(
                        content = current.content,
                        modifier = Modifier.weight(1f),
                    )
                }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderPages(
    content: ChapterContent,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(content.pages) { index, page ->
            ReaderPageImage(page = page, index = index)
        }
    }
}

@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    index: Int,
) {
    val context = LocalContext.current
    val widthPx = context.resources.displayMetrics.widthPixels
    val request =
        remember(page.url, page.splitCount, widthPx) {
            ImageRequest.Builder(context)
                .data(page.url)
                .size(widthPx, MAX_READER_HEIGHT_PX)
                .scale(Scale.FIT)
                .apply {
                    if (page.splitCount > 0) {
                        transformations(ScrambleTransformation(page.splitCount))
                    }
                }
                .crossfade(true)
                .build()
        }
    val painter = rememberAsyncImagePainter(request)
    val source = painter.intrinsicSize
    val ratio =
        if (source.width.isFinite() && source.height.isFinite() && source.width > 0f && source.height > 0f) {
            source.width / source.height
        } else {
            3f / 4f
        }

    Image(
        painter = painter,
        contentDescription = stringResource(R.string.reader_page, index + 1),
        contentScale = ContentScale.FillWidth,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

private const val MAX_READER_HEIGHT_PX = 4096
