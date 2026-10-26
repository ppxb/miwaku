package com.ppxb.miwaku.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.ui.navigation.MainTab

private val BarHeight = 64.dp
private val IndicatorHeight = 56.dp
private val CapsuleInset = 6.dp
private val MaxTabWidth = 96.dp
private val SearchButtonSize = 56.dp
private val CapsuleShape = RoundedCornerShape(BarHeight / 2)

/**
 * SimpMusic-style floating bar: one rounded capsule of tabs plus a separate round search button.
 * The capsule caps each tab at [MaxTabWidth], so on tablets it stays a compact centred cluster
 * instead of stretching across the window.
 *
 * [liquidGlass] swaps the flat surfaces for a translucent, rimmed pair of panels. It is the beta
 * stand-in for SimpMusic's backdrop blur: it lightens the material without capturing what is
 * behind the bar, which the empty MVP screens have nothing of yet.
 */
@Composable
fun AppBottomBar(
    selectedTab: MainTab?,
    onTabSelect: (MainTab) -> Unit,
    searchSelected: Boolean,
    onSearchClick: () -> Unit,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val tabWidth =
                ((maxWidth - CapsuleInset * 2) / MainTab.entries.size)
                    .coerceAtMost(MaxTabWidth)
            TabCapsule(
                selectedTab = selectedTab,
                tabWidth = tabWidth,
                onTabSelect = onTabSelect,
                liquidGlass = liquidGlass,
            )
        }
        Spacer(Modifier.width(12.dp))
        SearchButton(selected = searchSelected, onClick = onSearchClick, liquidGlass = liquidGlass)
    }
}

@Composable
private fun TabCapsule(
    selectedTab: MainTab?,
    tabWidth: Dp,
    onTabSelect: (MainTab) -> Unit,
    liquidGlass: Boolean,
) {
    val tabs = MainTab.entries
    val selectedIndex = selectedTab?.ordinal ?: -1
    val indicatorOffset by
        animateDpAsState(
            targetValue = tabWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tabIndicator",
        )
    val indicatorColor =
        if (liquidGlass) {
            MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }

    Box(
        modifier =
            Modifier
                .width(tabWidth * tabs.size + CapsuleInset * 2)
                .height(BarHeight)
                .clip(CapsuleShape)
                .background(barSurface(liquidGlass))
                .glassRim(liquidGlass, CapsuleShape),
    ) {
        // The indicator and the tabs share one inset coordinate space, so it always lines up.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = CapsuleInset),
        ) {
            if (selectedIndex >= 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            // Lambda overload: the indicator moves in the layout phase, so the
                            // animation never triggers a recomposition of the bar.
                            .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                            .width(tabWidth)
                            .height(IndicatorHeight)
                            .clip(RoundedCornerShape(IndicatorHeight / 2))
                            .background(indicatorColor),
                )
            }
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    TabItem(
                        tab = tab,
                        selected = tab == selectedTab,
                        onClick = { onTabSelect(tab) },
                        modifier =
                            Modifier
                                .width(tabWidth)
                                .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(IndicatorHeight / 2))
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchButton(
    selected: Boolean,
    onClick: () -> Unit,
    liquidGlass: Boolean,
) {
    val background =
        if (selected) {
            SolidColor(
                if (liquidGlass) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
        } else {
            barSurface(liquidGlass)
        }

    Box(
        modifier =
            Modifier
                .size(SearchButtonSize)
                .clip(CircleShape)
                .background(background)
                .glassRim(liquidGlass, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.action_search),
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun barSurface(liquidGlass: Boolean): Brush =
    if (liquidGlass) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
            ),
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainer)
    }

@Composable
private fun Modifier.glassRim(
    liquidGlass: Boolean,
    shape: Shape,
): Modifier =
    if (liquidGlass) {
        border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            shape = shape,
        )
    } else {
        this
    }
