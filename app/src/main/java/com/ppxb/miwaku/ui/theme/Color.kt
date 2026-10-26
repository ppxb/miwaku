package com.ppxb.miwaku.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 TonalSpot palettes derived from the brand seed `#8ECAE6`. Light neutrals sit on a
 * neutral-grey ramp; dark keeps the generated blue-tinted neutrals.
 */
internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF116682),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBDE9FF),
        onPrimaryContainer = Color(0xFF004D64),
        inversePrimary = Color(0xFF8BD0EF),
        secondary = Color(0xFF4D616C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD0E6F2),
        onSecondaryContainer = Color(0xFF354A53),
        tertiary = Color(0xFF5D5B7D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3DFFF),
        onTertiaryContainer = Color(0xFF454364),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFE2E2E2),
        onSurfaceVariant = Color(0xFF474747),
        surfaceTint = Color(0xFF116682),
        inverseSurface = Color(0xFF303030),
        inverseOnSurface = Color(0xFFF1F1F1),
        outline = Color(0xFF777777),
        outlineVariant = Color(0xFFC7C7C7),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDADADA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF1F1F1),
        surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8BD0EF),
        onPrimary = Color(0xFF003546),
        primaryContainer = Color(0xFF004D64),
        onPrimaryContainer = Color(0xFFBDE9FF),
        inversePrimary = Color(0xFF116682),
        secondary = Color(0xFFB4CAD6),
        onSecondary = Color(0xFF1F333C),
        secondaryContainer = Color(0xFF354A53),
        onSecondaryContainer = Color(0xFFD0E6F2),
        tertiary = Color(0xFFC6C2EA),
        onTertiary = Color(0xFF2E2D4D),
        tertiaryContainer = Color(0xFF454364),
        onTertiaryContainer = Color(0xFFE3DFFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F1417),
        onBackground = Color(0xFFDFE3E7),
        surface = Color(0xFF0F1417),
        onSurface = Color(0xFFDFE3E7),
        surfaceVariant = Color(0xFF40484C),
        onSurfaceVariant = Color(0xFFC0C8CD),
        surfaceTint = Color(0xFF8BD0EF),
        inverseSurface = Color(0xFFDFE3E7),
        inverseOnSurface = Color(0xFF2C3134),
        outline = Color(0xFF8A9297),
        outlineVariant = Color(0xFF40484C),
        surfaceBright = Color(0xFF353A3D),
        surfaceDim = Color(0xFF0F1417),
        surfaceContainerLowest = Color(0xFF0A0F11),
        surfaceContainerLow = Color(0xFF171C1F),
        surfaceContainer = Color(0xFF1B2023),
        surfaceContainerHigh = Color(0xFF262B2D),
        surfaceContainerHighest = Color(0xFF303538),
    )
