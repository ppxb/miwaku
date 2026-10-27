package com.ppxb.miwaku.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** Frosted top-bar material tinted by the page surface; haze falls back to an opaque fill. */
@Composable
internal fun topBarBlurStyle(alpha: Float = 1f): HazeBlurStyle {
    val surface = MaterialTheme.colorScheme.surface
    val tint = surface.copy(alpha = alpha)
    return HazeMaterials.ultraThin(tint).then {
        fallbackColorEffect(HazeColorEffect.tint(tint))
    }
}
