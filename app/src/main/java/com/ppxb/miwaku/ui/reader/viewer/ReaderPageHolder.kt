package com.ppxb.miwaku.ui.reader.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.reader.image.PageImageRequests
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import com.ppxb.miwaku.ui.reader.source.PageSource
import java.io.File
import kotlinx.coroutines.delay

/** How many times a page is silently re-fetched before the manual retry appears. */
private const val MAX_AUTO_RETRIES = 2

/** Lets the failed request settle before re-fetching, so a flapping connection is not hammered. */
private const val AUTO_RETRY_DELAY_MS = 600L

private sealed interface PageLoad {
    data object Loading : PageLoad

    data class Ready(val model: Any) : PageLoad

    data object Failed : PageLoad
}

/**
 * Renders one page and owns its whole load cycle: resolve the source, decode it, and recover from
 * the two ways a page can fail.
 *
 * A page can be missing because the journal evicted it between the existence check and the decode,
 * or because the download failed. Both are retried twice before the reader is asked to do anything.
 */
@Composable
internal fun ReaderPageHolder(
    page: ReaderPage,
    imageWidthPx: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    load: suspend (ReaderPage, Boolean) -> PageSource?,
) {
    var attempt by remember(page.key) { mutableIntStateOf(0) }
    var failed by remember(page.key) { mutableStateOf(false) }

    val loadState by
        produceState<PageLoad>(PageLoad.Loading, page.key, attempt) {
            val source = load(page, attempt > 0)
            value =
                when (val model = source?.coilModel()) {
                    null -> PageLoad.Failed
                    else -> PageLoad.Ready(model)
                }
        }

    LaunchedEffect(loadState) {
        failed = loadState is PageLoad.Failed
        if (loadState is PageLoad.Failed) ReaderPerf.log { "page_load_failed key=${page.key} attempt=$attempt" }
    }

    Box(
        modifier =
            modifier
                .clipToBounds()
                // Match the canvas so a page that is still decoding does not flash a dark block.
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = loadState) {
            PageLoad.Loading ->
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)

            PageLoad.Failed ->
                if (attempt < MAX_AUTO_RETRIES) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }

            is PageLoad.Ready ->
                PageArtwork(
                    page = page,
                    model = current.model,
                    imageWidthPx = imageWidthPx,
                    contentScale = contentScale,
                    attempt = attempt,
                    onDecodeFailed = { failed = true },
                )
        }
        if (failed && attempt >= MAX_AUTO_RETRIES) {
            FailureNotice(
                onRetry = {
                    attempt += 1
                    failed = false
                },
            )
        }
    }

    LaunchedEffect(failed, attempt) {
        if (!failed || attempt >= MAX_AUTO_RETRIES) return@LaunchedEffect
        delay(AUTO_RETRY_DELAY_MS)
        attempt += 1
        failed = false
        ReaderPerf.log { "page_auto_retry key=${page.key} attempt=$attempt" }
    }
}

@Composable
private fun PageArtwork(
    page: ReaderPage,
    model: Any,
    imageWidthPx: Int,
    contentScale: ContentScale,
    attempt: Int,
    onDecodeFailed: () -> Unit,
) {
    val context = LocalContext.current
    val request =
        remember(model, page.splitCount, imageWidthPx, attempt) {
            PageImageRequests.build(
                context = context,
                image = page.image,
                data = model,
                cacheKey = cacheKeyOf(model),
                widthPx = imageWidthPx,
                attempt = attempt,
            )
        }
    val painter = rememberAsyncImagePainter(model = request)
    val state by painter.state.collectAsStateWithLifecycle()
    var showSpinner by remember(request) { mutableStateOf(false) }
    val started = remember(request) { ReaderPerf.mark() }

    LaunchedEffect(state) {
        if (state is AsyncImagePainter.State.Loading) {
            delay(180)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }
    LaunchedEffect(state) {
        when (state) {
            is AsyncImagePainter.State.Success ->
                ReaderPerf.log { "page_ready key=${page.key} ms=${ReaderPerf.mark() - started}" }
            is AsyncImagePainter.State.Error -> {
                ReaderPerf.log { "page_decode_failed key=${page.key} attempt=$attempt" }
                onDecodeFailed()
            }
            else -> Unit
        }
    }

    Image(
        painter = painter,
        contentDescription = stringResource(R.string.reader_page, page.image.index + 1),
        contentScale = contentScale,
        modifier = Modifier.fillMaxSize(),
    )
    if (showSpinner) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun FailureNotice(onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.error_load_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/**
 * What Coil receives. Archive entries need a fetcher (added with CBZ support); until then a resolver
 * never produces one.
 */
private fun PageSource.coilModel(): Any? =
    when (this) {
        is PageSource.Cached -> file
        is PageSource.ArchiveEntry -> null
    }

/** Stable identity of a fetchable model, so a rewritten page invalidates the memory cache. */
private fun cacheKeyOf(model: Any): String =
    when (model) {
        is File -> "${model.absolutePath}:${model.length()}"
        else -> model.toString()
    }
