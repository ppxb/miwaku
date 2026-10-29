package com.ppxb.miwaku

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.settings.ThemeMode
import com.ppxb.miwaku.ui.MiwakuApp
import com.ppxb.miwaku.ui.theme.MiwakuTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val settings by lazy { AppGraph.settings }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppGraph.downloadManager.tasks
                    .map { tasks ->
                        tasks.any {
                            it.state == DownloadState.Queued || it.state == DownloadState.Running
                        }
                    }.distinctUntilChanged()
                    .collect { active ->
                        if (active) requestNotificationPermission()
                    }
            }
        }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
