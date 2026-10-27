package com.ppxb.miwaku.core.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/**
 * App-wide appearance settings. SharedPreferences rather than DataStore on purpose: a few values
 * read once at startup, so the first frame already has the right theme instead of flashing.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _liquidGlass = MutableStateFlow(prefs.getBoolean(KEY_LIQUID_GLASS, false))
    val liquidGlass: StateFlow<Boolean> = _liquidGlass.asStateFlow()

    private val _placeholderCovers = MutableStateFlow(prefs.getBoolean(KEY_PLACEHOLDER_COVERS, false))
    val placeholderCovers: StateFlow<Boolean> = _placeholderCovers.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    fun setLiquidGlass(enabled: Boolean) {
        _liquidGlass.value = enabled
        prefs.edit { putBoolean(KEY_LIQUID_GLASS, enabled) }
    }

    fun setPlaceholderCovers(enabled: Boolean) {
        _placeholderCovers.value = enabled
        prefs.edit { putBoolean(KEY_PLACEHOLDER_COVERS, enabled) }
    }

    private fun readThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.System
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LIQUID_GLASS = "liquid_glass"
        const val KEY_PLACEHOLDER_COVERS = "placeholder_covers"
    }
}
