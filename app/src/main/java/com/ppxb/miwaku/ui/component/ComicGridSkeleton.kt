package com.ppxb.miwaku.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Placeholder grid that mirrors ComicGrid while the feed is loading. */
@Composable
fun ComicGridSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val transition = rememberInfiniteTransition(label = "comicSkeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
        label = "comicSkeletonShimmer",
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(12) {
            Column(Modifier.fillMaxWidth()) {
                SkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(12.dp)),
                    shimmer = shimmer,
                )
                Spacer(Modifier.height(6.dp))
                SkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.85f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    shimmer = shimmer,
                )
                Spacer(Modifier.height(4.dp))
                SkeletonBlock(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    shimmer = shimmer,
                )
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier,
    shimmer: Float,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.55f)
    Box(
        modifier =
            modifier
                .background(base)
                .drawWithContent {
                    drawContent()
                    val start = -size.width + size.width * 2f * shimmer
                    drawRect(
                        brush =
                            Brush.linearGradient(
                                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                                start = Offset(start, 0f),
                                end = Offset(start + size.width, size.height),
                            ),
                    )
                },
    )
}
