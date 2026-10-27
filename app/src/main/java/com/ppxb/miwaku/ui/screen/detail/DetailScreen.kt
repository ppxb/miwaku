package com.ppxb.miwaku.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ppxb.miwaku.R
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.ui.component.ErrorState

@Composable
internal fun DetailScreen(
    onBack: () -> Unit,
    onOpenChapter: (albumId: String, chapterId: String) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        DetailTopBar(onBack = onBack)
        when (val current = state) {
            DetailUiState.Loading ->
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is DetailUiState.Failed ->
                ErrorState(
                    message = current.message,
                    onRetry = viewModel::load,
                    modifier = Modifier.weight(1f),
                )

            is DetailUiState.Content ->
                DetailContent(
                    detail = current.detail,
                    onOpenChapter = onOpenChapter,
                    placeholderCovers = placeholderCovers,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit) {
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
            text = stringResource(R.string.detail_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun DetailContent(
    detail: ComicDetail,
    onOpenChapter: (albumId: String, chapterId: String) -> Unit,
    placeholderCovers: Boolean,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                if (placeholderCovers) {
                    Box(
                        modifier =
                            Modifier
                                .width(112.dp)
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    AsyncImage(
                        model = detail.coverUrl,
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(112.dp)
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.author.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.detail_author, detail.author),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (detail.tags.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = detail.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (detail.description.isNotBlank()) {
            item {
                Text(
                    text = detail.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detail_chapters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.detail_chapter_count, detail.chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (detail.chapters.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.detail_no_chapters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(detail.chapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    onClick = { onOpenChapter(detail.id, chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterSummary,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
