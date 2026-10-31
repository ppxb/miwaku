package com.ppxb.miwaku.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ppxb.miwaku.core.data.download.DownloadManager
import com.ppxb.miwaku.core.data.download.DownloadStore
import com.ppxb.miwaku.core.data.favorites.FavoritesStore
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.settings.SettingsStore
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.EndpointHealth
import com.ppxb.miwaku.core.network.EndpointStatus
import com.ppxb.miwaku.core.network.NetworkMetrics
import com.ppxb.miwaku.core.network.NetworkMetricsInterceptor
import com.ppxb.miwaku.core.network.PersistentCookieJar
import com.ppxb.miwaku.core.network.ProbeRecord
import com.ppxb.miwaku.core.network.RateLimitInterceptor
import com.ppxb.miwaku.core.network.RetryInterceptor
import com.ppxb.miwaku.core.network.SharedEndpointStateStore
import com.ppxb.miwaku.core.network.UserAgentStore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

data class NetworkStats(
    val requests: Long,
    val failures: Long,
    val retries: Long,
    val failovers: Long,
    val throttled: Long,
    val averageMs: Long,
)

/** One endpoint row for the settings diagnostics. [coolingDownMs] is 0 when the endpoint is usable. */
data class EndpointStatusRow(
    val host: String,
    val latencyMs: Long,
    val failures: Int,
    val coolingDownMs: Long,
    val probe: ProbeStatus? = null,
    val serving: Boolean = false,
)

/** What the last probe said about a host, independent of its runtime health. */
enum class ProbeStatus { Ok, Degraded, Dead }

data class EndpointSummary(
    val probedAt: Long,
    val apiVersion: String,
    val chosenApi: String?,
    val chosenImage: String?,
    val api: List<EndpointStatusRow>,
    val image: List<EndpointStatusRow>,
)

object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val library: LibraryStore by lazy { LibraryStore(appContext) }

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
            // OkHttp defaults to 5 concurrent requests per host, which caps the reader's page
            // transfers long before PageImageStore's own budget is reached.
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 8
                },
            )
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

    /**
     * Probe transport: same cookies and timeouts as the API client, but without the business rate
     * limiter, retry layer or metrics. A probe that waits in a 3/s queue would otherwise report its
     * own queueing delay as endpoint latency, and with enough candidates starve the 6s call timeout.
     */
    private val probeHttpClient by lazy {
        baseHttpClient
            .newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val endpointStateStore by lazy { SharedEndpointStateStore(appContext) }
    private val userAgent by lazy { UserAgentStore(appContext).userAgent }
    private val apiClient by lazy {
        // First launch: start from the built-in bases instead of blocking the UI on /setting.
        endpointStateStore.seedBootstrapIfEmpty()
        ApiClient(apiHttpClient, endpointStateStore, probeHttpClient, userAgent)
    }
    val downloadStore by lazy { DownloadStore(appContext, library) }
    val pageStore by lazy {
        PageImageStore(
            context = appContext,
            userAgent = apiClient.userAgent(),
            httpClient = imageHttpClient,
            alternateImageHost = { host -> apiClient.nextImageHost(host) },
            localFile = { image ->
                downloadStore
                    .pageFile(image.chapterId, image.index, image.fileName)
                    .takeIf { it.isFile && it.length() > 0 }
            },
            cacheLimitBytes = { effectiveCacheLimitBytes() },
            imageHosts = { apiClient.imageHosts() },
        )
    }
    val comics: ComicRepository by lazy {
        ComicRepository(apiClient, library)
    }
    val downloadManager by lazy {
        val manager =
            DownloadManager(
                pageStore = pageStore,
                progress = progress,
                comics = comics,
                store = downloadStore,
                settings = settings,
                networkAllowed = { !settings.wifiOnlyDownloads.value || isUnmetered() },
            )
        watchDownloadNetwork(manager)
        manager
    }
    val history: HistoryStore by lazy { HistoryStore(library) }
    val favorites: FavoritesStore by lazy { FavoritesStore(appContext) }
    val progress: ProgressStore by lazy { ProgressStore(library) }
    val readerModes: ReaderModeStore by lazy { ReaderModeStore(appContext) }

    fun isPrefetchAllowed(): Boolean =
        !settings.wifiOnlyPrefetch.value || isUnmetered()

    fun effectiveCacheLimitBytes(): Long = settings.readerCacheLimitMb.value * 1024L * 1024L

    fun readerCacheSizeBytes(): Long = pageStore.cacheSizeBytes()

    fun networkMetrics(): NetworkStats {
        val snapshot = NetworkMetrics.snapshot()
        return NetworkStats(
            requests = snapshot.requests,
            failures = snapshot.failures,
            retries = snapshot.retries,
            failovers = snapshot.failovers,
            throttled = snapshot.throttled,
            averageMs = snapshot.averageMs,
        )
    }

    /** Pool/selection snapshot for the settings screen; never triggers a probe. */
    fun endpointSummary(): EndpointSummary {
        val snapshot = apiClient.endpointSummary()
        val now = System.currentTimeMillis()
        val apiHealth = snapshot.api.associateBy { it.url }
        val imageHealth = snapshot.image.associateBy { it.url }
        val apiRows =
            snapshot.apiProbes.toRows(apiHealth, snapshot.chosenApi, now)
                .ifEmpty { snapshot.api.toRows(snapshot.chosenApi, now) }
        val imageRows =
            snapshot.imageProbes.toRows(imageHealth, snapshot.chosenImage, now)
                .ifEmpty { snapshot.image.toRows(snapshot.chosenImage, now) }
        return EndpointSummary(
            probedAt = snapshot.probedAt,
            apiVersion = snapshot.apiVersion,
            chosenApi = snapshot.chosenApi?.toDisplayHost(),
            chosenImage = snapshot.chosenImage?.toDisplayHost(),
            api = apiRows,
            image = imageRows,
        )
    }

    private fun List<ProbeRecord>.toRows(
        health: Map<String, EndpointHealth>,
        chosen: String?,
        now: Long,
    ): List<EndpointStatusRow> =
        map { record ->
            val runtime = health[record.url]
            EndpointStatusRow(
                host = record.url.toDisplayHost(),
                latencyMs = record.latencyMs.takeIf { it > 0L } ?: runtime?.latencyMs ?: 0L,
                failures = runtime?.failures ?: 0,
                coolingDownMs = ((runtime?.cooldownUntil ?: 0L) - now).coerceAtLeast(0L),
                probe =
                    when (record.status) {
                        EndpointStatus.Ok -> ProbeStatus.Ok
                        EndpointStatus.Degraded -> ProbeStatus.Degraded
                        EndpointStatus.Dead -> ProbeStatus.Dead
                    },
                serving = record.url == chosen,
            )
        }.sortedWith(compareBy({ it.probe != ProbeStatus.Ok }, { it.probe == ProbeStatus.Dead }, { it.latencyMs }))

    private fun List<EndpointHealth>.toRows(
        chosen: String?,
        now: Long,
    ): List<EndpointStatusRow> =
        map {
            EndpointStatusRow(
                host = it.url.toDisplayHost(),
                latencyMs = it.latencyMs,
                failures = it.failures,
                coolingDownMs = (it.cooldownUntil - now).coerceAtLeast(0L),
                serving = it.url == chosen,
            )
        }

    private fun String.toDisplayHost(): String = substringAfter("://").trimEnd('/')

    suspend fun clearReaderCache() {
        pageStore.clearCache()
    }

    private fun isUnmetered(): Boolean =
        runCatching {
            val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)

    /** Resume a Wi-Fi-only queue as soon as an unmetered network appears. */
    private fun watchDownloadNetwork(manager: DownloadManager) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        manager.onNetworkAvailable()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        manager.onNetworkAvailable()
                    }
                },
            )
        }
    }
}
