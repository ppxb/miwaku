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

    private val _readerCacheLimitMb = MutableStateFlow(readCacheLimitMb())
    val readerCacheLimitMb: StateFlow<Int> = _readerCacheLimitMb.asStateFlow()

    private val _readerBackground = MutableStateFlow(readReaderBackground())
    val readerBackground: StateFlow<ReaderBackground> = _readerBackground.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_READER_KEEP_SCREEN_ON, false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showPageNumber = MutableStateFlow(prefs.getBoolean(KEY_READER_SHOW_PAGE_NUMBER, true))
    val showPageNumber: StateFlow<Boolean> = _showPageNumber.asStateFlow()

    private val _defaultReadingMode = MutableStateFlow(readReadingMode())
    val defaultReadingMode: StateFlow<ReadingMode> = _defaultReadingMode.asStateFlow()

    private val _pageLayout = MutableStateFlow(readPageLayout())
    val pageLayout: StateFlow<PageLayoutMode> = _pageLayout.asStateFlow()

    private val _downloadPageConcurrency = MutableStateFlow(readDownloadConcurrency())
    val downloadPageConcurrency: StateFlow<Int> = _downloadPageConcurrency.asStateFlow()

    private val _downloadChapterConcurrency = MutableStateFlow(readDownloadChapterConcurrency())
    val downloadChapterConcurrency: StateFlow<Int> = _downloadChapterConcurrency.asStateFlow()

    private val _wifiOnlyDownloads = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, false))
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads.asStateFlow()

    private val _downloadLimitMb = MutableStateFlow(readDownloadLimitMb())
    val downloadLimitMb: StateFlow<Int> = _downloadLimitMb.asStateFlow()

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

    fun setReaderCacheLimitMb(mb: Int) {
        val safe = mb.coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB)
        _readerCacheLimitMb.value = safe
        prefs.edit { putInt(KEY_CACHE_LIMIT_MB, safe) }
    }

    fun setReaderBackground(background: ReaderBackground) {
        _readerBackground.value = background
        prefs.edit { putString(KEY_READER_BACKGROUND, background.name) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        prefs.edit { putBoolean(KEY_READER_KEEP_SCREEN_ON, enabled) }
    }

    fun setShowPageNumber(enabled: Boolean) {
        _showPageNumber.value = enabled
        prefs.edit { putBoolean(KEY_READER_SHOW_PAGE_NUMBER, enabled) }
    }

    fun setDefaultReadingMode(mode: ReadingMode) {
        _defaultReadingMode.value = mode
        prefs.edit { putString(KEY_READER_DEFAULT_MODE, mode.name) }
    }

    fun setPageLayout(mode: PageLayoutMode) {
        _pageLayout.value = mode
        prefs.edit { putString(KEY_PAGE_LAYOUT, mode.name) }
    }

    fun setDownloadPageConcurrency(value: Int) {
        val safe = value.coerceIn(MIN_DOWNLOAD_CONCURRENCY, MAX_DOWNLOAD_CONCURRENCY)
        _downloadPageConcurrency.value = safe
        prefs.edit { putInt(KEY_DOWNLOAD_CONCURRENCY, safe) }
    }

    fun setDownloadChapterConcurrency(value: Int) {
        val safe = value.coerceIn(MIN_DOWNLOAD_CHAPTER_CONCURRENCY, MAX_DOWNLOAD_CHAPTER_CONCURRENCY)
        _downloadChapterConcurrency.value = safe
        prefs.edit { putInt(KEY_DOWNLOAD_CHAPTER_CONCURRENCY, safe) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        _wifiOnlyDownloads.value = enabled
        prefs.edit { putBoolean(KEY_WIFI_ONLY_DOWNLOADS, enabled) }
    }

    fun setDownloadLimitMb(mb: Int) {
        val safe = mb.coerceIn(MIN_DOWNLOAD_LIMIT_MB, MAX_DOWNLOAD_LIMIT_MB)
        _downloadLimitMb.value = safe
        prefs.edit { putInt(KEY_DOWNLOAD_LIMIT_MB, safe) }
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

    private fun readReaderBackground(): ReaderBackground {
        val stored = prefs.getString(KEY_READER_BACKGROUND, null) ?: return ReaderBackground.MatchTheme
        return ReaderBackground.entries.firstOrNull { it.name == stored } ?: ReaderBackground.MatchTheme
    }

    private fun readReadingMode(): ReadingMode {
        val stored = prefs.getString(KEY_READER_DEFAULT_MODE, null) ?: return ReadingMode.Webtoon
        return ReadingMode.entries.firstOrNull { it.name == stored } ?: ReadingMode.Webtoon
    }

    private fun readPageLayout(): PageLayoutMode {
        val stored = prefs.getString(KEY_PAGE_LAYOUT, null) ?: return PageLayoutMode.Automatic
        return PageLayoutMode.entries.firstOrNull { it.name == stored } ?: PageLayoutMode.Automatic
    }

    private fun readDownloadConcurrency(): Int =
        prefs.getInt(KEY_DOWNLOAD_CONCURRENCY, DEFAULT_DOWNLOAD_CONCURRENCY)
            .coerceIn(MIN_DOWNLOAD_CONCURRENCY, MAX_DOWNLOAD_CONCURRENCY)

    private fun readDownloadChapterConcurrency(): Int =
        prefs.getInt(KEY_DOWNLOAD_CHAPTER_CONCURRENCY, DEFAULT_DOWNLOAD_CHAPTER_CONCURRENCY)
            .coerceIn(MIN_DOWNLOAD_CHAPTER_CONCURRENCY, MAX_DOWNLOAD_CHAPTER_CONCURRENCY)

    private fun readDownloadLimitMb(): Int =
        prefs.getInt(KEY_DOWNLOAD_LIMIT_MB, DEFAULT_DOWNLOAD_LIMIT_MB)
            .coerceIn(MIN_DOWNLOAD_LIMIT_MB, MAX_DOWNLOAD_LIMIT_MB)

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LIQUID_GLASS = "liquid_glass"
        const val KEY_PLACEHOLDER_COVERS = "placeholder_covers"
        const val KEY_PREFETCH_AHEAD = "reader_prefetch_ahead"
        const val KEY_WIFI_ONLY_PREFETCH = "wifi_only_prefetch"
        const val KEY_CACHE_LIMIT_MB = "reader_cache_limit_mb"
        const val KEY_READER_BACKGROUND = "reader_background"
        const val KEY_READER_KEEP_SCREEN_ON = "reader_keep_screen_on"
        const val KEY_READER_SHOW_PAGE_NUMBER = "reader_show_page_number"
        const val KEY_READER_DEFAULT_MODE = "reader_default_mode"
        const val KEY_PAGE_LAYOUT = "reader_page_layout"
        const val KEY_DOWNLOAD_CONCURRENCY = "download_page_concurrency"
        const val KEY_DOWNLOAD_CHAPTER_CONCURRENCY = "download_chapter_concurrency"
        const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        const val KEY_DOWNLOAD_LIMIT_MB = "download_limit_mb"
        const val DEFAULT_DOWNLOAD_CONCURRENCY = 5
        const val MIN_DOWNLOAD_CONCURRENCY = 1
        const val MAX_DOWNLOAD_CONCURRENCY = 5
        const val DEFAULT_DOWNLOAD_CHAPTER_CONCURRENCY = 3
        const val MIN_DOWNLOAD_CHAPTER_CONCURRENCY = 1
        const val MAX_DOWNLOAD_CHAPTER_CONCURRENCY = 5
        const val DEFAULT_DOWNLOAD_LIMIT_MB = 0
        const val MIN_DOWNLOAD_LIMIT_MB = 0
        const val MAX_DOWNLOAD_LIMIT_MB = 16384
        const val DEFAULT_PREFETCH_AHEAD = 10
        const val MIN_PREFETCH_AHEAD = 4
        const val MAX_PREFETCH_AHEAD = 20
        const val DEFAULT_CACHE_LIMIT_MB = 1024
        const val MIN_CACHE_LIMIT_MB = 512
        const val MAX_CACHE_LIMIT_MB = 2048
    }
}
