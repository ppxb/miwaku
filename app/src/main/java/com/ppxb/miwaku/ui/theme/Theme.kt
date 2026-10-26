package com.ppxb.miwaku.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun MiwakuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // The theme mode can be forced from Settings, so the system bar icons have to follow the
    // resolved theme rather than the device's night mode that enableEdgeToEdge() read at launch.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            WindowCompat.getInsetsController((view.context as Activity).window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MiwakuTypography,
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides SoftRipple,
            LocalContentColor provides colorScheme.onSurfaceVariant,
            content = content,
        )
    }
}

/** A quieter touch response than Material's default. */
@OptIn(ExperimentalMaterial3Api::class)
private val SoftRipple =
    RippleConfiguration(
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = 0.06f,
                focusedAlpha = 0.04f,
                hoveredAlpha = 0.03f,
                pressedAlpha = 0.06f,
            ),
    )
