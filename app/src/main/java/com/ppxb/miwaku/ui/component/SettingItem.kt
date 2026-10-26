package com.ppxb.miwaku.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val GroupShape = RoundedCornerShape(20.dp)
private val RowPadding = 16.dp
private val DividerThickness = 0.5.dp

/** A settings section: a small header above one rounded card holding the rows. */
@Composable
fun SettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = RowPadding, end = RowPadding, bottom = 8.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(GroupShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .drawWithContent {
                        // Each row draws its own top hairline; the first one would sit on the card
                        // edge, so the card clips that strip away.
                        clipRect(top = DividerThickness.toPx()) { this@drawWithContent.drawContent() }
                    },
            content = content,
        )
    }
}

@Composable
fun SettingItem(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    switch: Pair<Boolean, (Boolean) -> Unit>? = null,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = DividerThickness.toPx() / 2
                    val inset = RowPadding.toPx()
                    drawLine(
                        color = dividerColor,
                        start = Offset(inset, y),
                        end = Offset(size.width, y),
                        strokeWidth = DividerThickness.toPx(),
                    )
                }.then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RowPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (switch != null) {
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = switch.first,
                    onCheckedChange = switch.second,
                )
            }
        }
    }
}
