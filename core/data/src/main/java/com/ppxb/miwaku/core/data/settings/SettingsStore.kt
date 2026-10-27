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

    private val _readerPrefetchAhead = MutableStateFlow(readPrefetchAhead())
    val readerPrefetchAhead: StateFlow<Int> = _readerPrefetchAhead.asStateFlow()

    private val _wifiOnlyPrefetch = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY_PREFETCH, false))
    val wifiOnlyPrefetch: StateFlow<Boolean> = _wifiOnlyPrefetch.asStateFlow()

    private val _lowMemory = MutableStateFlow(prefs.getBoolean(KEY_LOW_MEMORY, false))
    val lowMemory: StateFlow<Boolean> = _lowMemory.asStateFlow()

    private val _readerCacheLimitMb = MutableStateFlow(readCacheLimitMb())
    val readerCacheLimitMb: StateFlow<Int> = _readerCacheLimitMb.asStateFlow()

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

    fun setReaderPrefetchAhead(pages: Int) {
        val safe = pages.coerceIn(MIN_PREFETCH_AHEAD, MAX_PREFETCH_AHEAD)
        _readerPrefetchAhead.value = safe
        prefs.edit { putInt(KEY_PREFETCH_AHEAD, safe) }
    }

    fun setWifiOnlyPrefetch(enabled: Boolean) {
        _wifiOnlyPrefetch.value = enabled
        prefs.edit { putBoolean(KEY_WIFI_ONLY_PREFETCH, enabled) }
    }

    fun setLowMemory(enabled: Boolean) {
        _lowMemory.value = enabled
        prefs.edit { putBoolean(KEY_LOW_MEMORY, enabled) }
    }

    fun setReaderCacheLimitMb(mb: Int) {
        val safe = mb.coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB)
        _readerCacheLimitMb.value = safe
        prefs.edit { putInt(KEY_CACHE_LIMIT_MB, safe) }
    }

    private fun readCacheLimitMb(): Int =
        prefs.getInt(KEY_CACHE_LIMIT_MB, DEFAULT_CACHE_LIMIT_MB)
            .coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB)

    private fun readPrefetchAhead(): Int =
        prefs.getInt(KEY_PREFETCH_AHEAD, DEFAULT_PREFETCH_AHEAD)
            .coerceIn(MIN_PREFETCH_AHEAD, MAX_PREFETCH_AHEAD)

    private fun readThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.System
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LIQUID_GLASS = "liquid_glass"
        const val KEY_PLACEHOLDER_COVERS = "placeholder_covers"
        const val KEY_PREFETCH_AHEAD = "reader_prefetch_ahead"
        const val KEY_WIFI_ONLY_PREFETCH = "wifi_only_prefetch"
        const val KEY_LOW_MEMORY = "low_memory"
        const val KEY_CACHE_LIMIT_MB = "reader_cache_limit_mb"
        const val DEFAULT_PREFETCH_AHEAD = 6
        const val MIN_PREFETCH_AHEAD = 3
        const val MAX_PREFETCH_AHEAD = 10
        const val DEFAULT_CACHE_LIMIT_MB = 1024
        const val MIN_CACHE_LIMIT_MB = 512
        const val MAX_CACHE_LIMIT_MB = 2048
    }
}
