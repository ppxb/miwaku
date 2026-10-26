package com.ppxb.miwaku.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val GlowHeight = 360.dp
private val ScrimHeight = 180.dp
private const val GlowAngleDegrees = 25f
private const val EaseSteps = 24

/**
 * SimpMusic's ambient ground for screens without artwork: a [GlowHeight] angled wash in a pastel
 * (light) or deepened (dark) version of [tint], melting into the page through a bottom scrim.
 *
 * Emit it as a sibling behind the screen content.
 */
@Composable
fun AmbientGlow(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val lightTheme = background.luminance() > 0.5f
    val glow =
        if (lightTheme) {
            lerp(tint, Color.White, 0.85f)
        } else {
            Color(
                red = tint.red * 0.45f,
                green = tint.green * 0.45f,
                blue = tint.blue * 0.45f,
                alpha = tint.alpha,
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(GlowHeight)
                .angledBackground(listOf(glow, background)),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ScrimHeight)
                .background(scrimBrush(background)),
        )
    }
}

private fun Modifier.angledBackground(colors: List<Color>): Modifier =
    drawBehind {
        val radians = Math.toRadians(GlowAngleDegrees.toDouble()).toFloat()
        val dx = cos(radians)
        val dy = sin(radians)
        val length = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
        drawRect(
            brush =
                Brush.linearGradient(
                    colorStops = easedStops(colors),
                    start = Offset(center.x - dx * length / 2, center.y - dy * length / 2),
                    end = Offset(center.x + dx * length / 2, center.y + dy * length / 2),
                ),
        )
    }

// Smoothstep stops: a two-stop ramp leaves a visible corner where the tone leaves the page colour.
private fun easedStops(colors: List<Color>): Array<Pair<Float, Color>> {
    val segments = colors.size - 1
    val total = segments * EaseSteps
    return Array(total + 1) { i ->
        val segment = minOf(i / EaseSteps, segments - 1)
        val t = (i - segment * EaseSteps) / EaseSteps.toFloat()
        i / total.toFloat() to lerp(colors[segment], colors[segment + 1], t * t * (3f - 2f * t))
    }
}

// Fade to the page colour without dragging RGB through black, which a Transparent stop would.
private fun scrimBrush(color: Color): Brush =
    Brush.verticalGradient(
        colorStops =
            Array(EaseSteps + 1) { i ->
                val t = i / EaseSteps.toFloat()
                t to lerp(color.copy(alpha = 0f), color, t * t * (3f - 2f * t))
            },
    )
