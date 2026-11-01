package com.ppxb.miwaku.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Bottom inset the floating navigation bar needs, shared by the tab pages. */
@Composable
fun navBarBottomInset() =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp

/**
 * Chrome shared by the top-level tabs whose content is one grid.
 *
 * The heading scrolls away with the cards and the ambient glow leaves with it; once the heading is
 * gone a compact bar fades in. Pages put [content]'s header argument as the first item of their
 * grid, so heading and cards share one scroll and the glow stays behind both.
 */
@Composable
fun NavPageScaffold(
    title: String,
    subtitle: String,
    scrollState: LazyGridState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    headerExtra: (@Composable () -> Unit)? = null,
    content: @Composable (header: @Composable () -> Unit) -> Unit,
) {
    val hazeState = rememberHazeState()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val bottomInset = navBarBottomInset()
    val headerGone by remember { derivedStateOf { scrollState.firstVisibleItemIndex > 0 } }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            AmbientGlow(
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY =
                                if (scrollState.firstVisibleItemIndex == 0) {
                                    -scrollState.firstVisibleItemScrollOffset.toFloat()
                                } else {
                                    -headerHeightPx.toFloat()
                                }
                        },
            )
            Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                content {
                    NavPageHeader(
                        title = title,
                        subtitle = subtitle,
                        actions = actions,
                        extra = headerExtra,
                        onSize = { headerHeightPx = it },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = headerGone,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            NavPageTopBar(
                title = title,
                actions = actions,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hazeBlur(HazeInput.Sources(hazeState), topBarBlurStyle())
                        .statusBarsPadding(),
            )
        }
    }
}

/**
 * Large heading used by every tab: a headline, one line of context, and the page's actions.
 * [onSize] lets a scrolling page keep the glow in step with the heading.
 */
@Composable
fun NavPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    extra: (@Composable () -> Unit)? = null,
    onSize: (Int) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 8.dp)
            .onSizeChanged { onSize(it.height) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            actions()
        }
        if (extra != null) {
            Spacer(Modifier.height(8.dp))
            extra()
        }
    }
}

/** Compact bar that takes over once the heading scrolls away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavPageTopBar(
    title: String,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        windowInsets = WindowInsets(0),
    )
}
