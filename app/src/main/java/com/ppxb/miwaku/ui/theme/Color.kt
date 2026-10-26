package com.ppxb.miwaku.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Same seed as SimpMusic, `#8ECAE6` (a sky blue), with the TonalSpot roles derived from it.
 * Light neutrals stay on the neutral-grey ramp SimpMusic pins in light mode; dark neutrals carry
 * the seed's faint blue cast.
 */
internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF00687A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB4EBF8),
        onPrimaryContainer = Color(0xFF001F26),
        inversePrimary = Color(0xFF8ECAE6),
        secondary = Color(0xFF4A6268),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDE7EE),
        onSecondaryContainer = Color(0xFF051F25),
        tertiary = Color(0xFF5B5D7E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE1E0FA),
        onTertiaryContainer = Color(0xFF171A34),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFE4E4E4),
        onSurfaceVariant = Color(0xFF474747),
        surfaceTint = Color(0xFF00687A),
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
        primary = Color(0xFF8ECAE6),
        onPrimary = Color(0xFF003640),
        primaryContainer = Color(0xFF004E5B),
        onPrimaryContainer = Color(0xFFB4EBF8),
        inversePrimary = Color(0xFF00687A),
        secondary = Color(0xFFB1CBD2),
        onSecondary = Color(0xFF1C343A),
        secondaryContainer = Color(0xFF334B51),
        onSecondaryContainer = Color(0xFFCDE7EE),
        tertiary = Color(0xFFC4C2E6),
        onTertiary = Color(0xFF2C2E4C),
        tertiaryContainer = Color(0xFF424563),
        onTertiaryContainer = Color(0xFFE1E0FA),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF101316),
        onBackground = Color(0xFFE1E2E6),
        surface = Color(0xFF101316),
        onSurface = Color(0xFFE1E2E6),
        surfaceVariant = Color(0xFF3F484B),
        onSurfaceVariant = Color(0xFFBFC8CC),
        surfaceTint = Color(0xFF8ECAE6),
        inverseSurface = Color(0xFFE1E2E6),
        inverseOnSurface = Color(0xFF2E3134),
        outline = Color(0xFF89939A),
        outlineVariant = Color(0xFF3F484B),
        surfaceBright = Color(0xFF363A3D),
        surfaceDim = Color(0xFF101316),
        surfaceContainerLowest = Color(0xFF0B0E11),
        surfaceContainerLow = Color(0xFF181C1F),
        surfaceContainer = Color(0xFF1C2023),
        surfaceContainerHigh = Color(0xFF262A2D),
        surfaceContainerHighest = Color(0xFF313538),
    )
