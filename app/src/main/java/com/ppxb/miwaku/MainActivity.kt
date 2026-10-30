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
import com.ppxb.miwaku.download.DownloadService
import com.ppxb.miwaku.ui.MiwakuApp
import com.ppxb.miwaku.ui.theme.MiwakuTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.fillMaxSize
import com.ppxb.miwaku.core.data.LibraryStartup

class MainActivity : ComponentActivity() {
    private val settings by lazy { AppGraph.settings }
    private var notificationPermissionRequested = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppGraph.library.startup.first { it == LibraryStartup.Ready }
                val downloads = AppGraph.downloadManager
                combine(downloads.tasks, downloads.isPaused) { tasks, paused ->
                    !paused &&
                        tasks.any {
                            it.state == DownloadState.Queued || it.state == DownloadState.Running
                        }
                }.distinctUntilChanged()
                    .collect { active ->
                        if (active) {
                            // Activity is resumed here, so Android 12+ allows a dataSync FGS start.
                            DownloadService.start(this@MainActivity)
                            requestNotificationPermission()
                        }
                    }
            }
        }
        setContent {
            val startup by AppGraph.library.startup.collectAsState()
            if (startup != LibraryStartup.Ready) {
                MiwakuTheme {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        if (startup == LibraryStartup.Loading) androidx.compose.material3.Text("正在准备本地阅读数据…")
                        else androidx.compose.material3.Button(onClick = { AppGraph.library.retryInitialization() }) {
                            androidx.compose.material3.Text("本地数据迁移失败，原数据已保留。点击重试")
                        }
                    }
                }
                return@setContent
            }
            val themeMode by settings.themeMode.collectAsState()
            val liquidGlass by settings.liquidGlass.collectAsState()
            val placeholderCovers by settings.placeholderCovers.collectAsState()
            val prefetchAhead by settings.readerPrefetchAhead.collectAsState()
            val wifiOnlyPrefetch by settings.wifiOnlyPrefetch.collectAsState()
            val lowMemory by settings.lowMemory.collectAsState()
            val cacheLimitMb by settings.readerCacheLimitMb.collectAsState()
            val downloadConcurrency by settings.downloadPageConcurrency.collectAsState()
            val wifiOnlyDownloads by settings.wifiOnlyDownloads.collectAsState()
            val downloadLimitMb by settings.downloadLimitMb.collectAsState()
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
                    downloadLimitMb = downloadLimitMb,
                    onDownloadLimitChange = settings::setDownloadLimitMb,
                    keepScreenOn = keepScreenOn,
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionRequested = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
