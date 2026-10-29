package com.ppxb.miwaku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.settings.ThemeMode
import com.ppxb.miwaku.ui.MiwakuApp
import com.ppxb.miwaku.ui.theme.MiwakuTheme

class MainActivity : ComponentActivity() {
    private val settings by lazy { AppGraph.settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settings.themeMode.collectAsState()
            val liquidGlass by settings.liquidGlass.collectAsState()
            val placeholderCovers by settings.placeholderCovers.collectAsState()
            val prefetchAhead by settings.readerPrefetchAhead.collectAsState()
            val wifiOnlyPrefetch by settings.wifiOnlyPrefetch.collectAsState()
            val lowMemory by settings.lowMemory.collectAsState()
            val cacheLimitMb by settings.readerCacheLimitMb.collectAsState()
            val downloadConcurrency by settings.downloadPageConcurrency.collectAsState()
            val wifiOnlyDownloads by settings.wifiOnlyDownloads.collectAsState()
            val keepScreenOn by settings.keepScreenOn.collectAsState()
            val darkTheme =
                when (themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }

            MiwakuTheme(darkTheme = darkTheme) {
                MiwakuApp(
                    themeMode = themeMode,
                    onThemeModeChange = settings::setThemeMode,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = settings::setLiquidGlass,
                    placeholderCovers = placeholderCovers,
                    onPlaceholderCoversChange = settings::setPlaceholderCovers,
                    prefetchAhead = prefetchAhead,
                    onPrefetchAheadChange = settings::setReaderPrefetchAhead,
                    wifiOnlyPrefetch = wifiOnlyPrefetch,
                    onWifiOnlyPrefetchChange = settings::setWifiOnlyPrefetch,
                    lowMemory = lowMemory,
                    onLowMemoryChange = settings::setLowMemory,
                    cacheLimitMb = cacheLimitMb,
                    onCacheLimitMbChange = settings::setReaderCacheLimitMb,
                    downloadConcurrency = downloadConcurrency,
                    onDownloadConcurrencyChange = settings::setDownloadPageConcurrency,
                    wifiOnlyDownloads = wifiOnlyDownloads,
                    onWifiOnlyDownloadsChange = settings::setWifiOnlyDownloads,
                    keepScreenOn = keepScreenOn,
                )
            }
        }
    }
}
