package com.ppxb.miwaku.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ppxb.miwaku.core.data.image.ChapterManifestCache
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.settings.SettingsStore
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.NetworkMetricsInterceptor
import com.ppxb.miwaku.core.network.PersistentCookieJar
import com.ppxb.miwaku.core.network.RateLimitInterceptor
import com.ppxb.miwaku.core.network.RetryInterceptor
import com.ppxb.miwaku.core.network.SharedSettingSnapshotStore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    private val cookieJar by lazy { PersistentCookieJar(appContext) }

    /**
     * Shared transport settings. The API and image clients derive from it so a limit added to one
     * (rate limiting) can never throttle the other.
     */
    private val baseHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(cookieJar)
            .build()
    }

    private val apiHttpClient by lazy {
        baseHttpClient
            .newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 1))
            .addInterceptor(RateLimitInterceptor(permits = 3, period = 1.seconds))
            .addInterceptor(NetworkMetricsInterceptor())
            .build()
    }

    private val imageHttpClient by lazy {
        baseHttpClient
            .newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 1))
            .build()
    }

    private val settingStore by lazy { SharedSettingSnapshotStore(appContext) }
    private val apiClient by lazy { ApiClient(apiHttpClient, settingStore) }
    private val manifests by lazy { ChapterManifestCache(appContext) }
    private val detailStore by lazy { ComicDetailCache(appContext) }
    val pageStore by lazy {
        PageImageStore(
            context = appContext,
            userAgent = apiClient.userAgent(),
            httpClient = imageHttpClient,
            alternateImageHost = { apiClient.refreshImageHost() },
            cacheLimitBytes = { effectiveCacheLimitBytes() },
        )
    }
    val comics: ComicRepository by lazy { ComicRepository(apiClient, manifests, detailStore) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }
    val progress: ProgressStore by lazy { ProgressStore(appContext) }
    val readerModes: ReaderModeStore by lazy { ReaderModeStore(appContext) }

    fun isPrefetchAllowed(): Boolean =
        !settings.wifiOnlyPrefetch.value || isUnmetered()

    fun effectiveCacheLimitBytes(): Long {
        val selected = settings.readerCacheLimitMb.value
        val capped =
            if (settings.lowMemory.value) {
                minOf(selected, LOW_MEMORY_CACHE_LIMIT_MB)
            } else {
                selected
            }
        return capped * 1024L * 1024L
    }

    fun readerCacheSizeBytes(): Long =
        pageStore.cacheSizeBytes() + manifests.sizeBytes() + detailStore.sizeBytes()

    fun clearReaderCache() {
        pageStore.clearCache()
        manifests.clear()
        detailStore.clear()
    }

    private fun isUnmetered(): Boolean =
        runCatching {
            val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)

    private const val LOW_MEMORY_CACHE_LIMIT_MB = 256
}
