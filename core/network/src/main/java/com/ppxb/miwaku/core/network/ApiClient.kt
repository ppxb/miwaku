package com.ppxb.miwaku.core.network

import android.util.Log
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

data class SettingSnapshot(
    val baseUrl: String,
    val version: String,
    val imageHost: String,
)

data class ApiPayload(
    val value: Any,
    val imageHost: String,
)

/**
 * Single-source JM API client.
 *
 * Responsibilities kept here on purpose: the setting snapshot (base/version/image host), the
 * failure classification, and the failover policy. Interceptors only handle transport concerns.
 */
class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val settingStore: SettingSnapshotStore? = null,
    private val poolStore: EndpointPoolStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domainResolver = DomainResolver(httpClient)
    private val settingLock = Mutex()
    private val imageHostLock = Mutex()
    private val poolRefreshLock = Mutex()

    @Volatile
    private var snapshot: SettingSnapshot? = null

    @Volatile
    private var orderedBases: List<String> = emptyList()

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var poolLoaded = false

    @Volatile
    private var nextPoolRefreshAt = 0L

    @Volatile
    private var poolRefreshJob: Job? = null

    @Volatile
    private var lastPoolAttemptAt = 0L

    @Volatile
    private var lastPoolSuccessAt = 0L

    @Volatile
    private var settingJob: Deferred<SettingSnapshot>? = null

    @Volatile
    private var lastImageHostRefreshAt = 0L

    private fun cachedSnapshot(): SettingSnapshot? {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    snapshot = runCatching { settingStore?.read() }.getOrNull()
                    cacheLoaded = true
                }
            }
        }
        if (!poolLoaded) loadPool()
        return snapshot
    }

    private fun loadPool() {
        if (poolLoaded) return
        synchronized(this) {
            if (poolLoaded) return
            val pool = runCatching { poolStore?.read() }.getOrNull()
            orderedBases = pool?.orderedBases.orEmpty()
            nextPoolRefreshAt =
                when {
                    pool != null -> pool.updatedAt + POOL_TTL_MS
                    poolStore == null -> Long.MAX_VALUE
                    else -> 0L
                }
            poolLoaded = true
        }
    }

    /**
     * Refreshes the persisted pool in the background when it is missing or older than
     * [POOL_TTL_MS]. The cached snapshot keeps the first request instant; the refresh replaces it
     * only after a successful probe.
     */
    private fun schedulePoolRefreshIfNeeded() {
        if (System.currentTimeMillis() < nextPoolRefreshAt) return
        if (poolRefreshJob?.isActive == true) return
        poolRefreshJob = scope.launch { refreshPool(force = false) }
    }

    /**
     * Returns the current snapshot, fetching `/setting` at most once for concurrent callers.
     * Unlike the previous implementation this never holds a lock while doing network I/O.
     */
    suspend fun ensureSetting(): SettingSnapshot {
        cachedSnapshot()?.let {
            schedulePoolRefreshIfNeeded()
            return it
        }
        val inFlight =
            settingLock.withLock {
                cachedSnapshot()?.let { return@withLock null }
                settingJob?.takeIf { it.isActive }
                    ?: run {
                        val job = scope.async(start = CoroutineStart.LAZY) { loadSetting() }
                        settingJob = job
                        job.invokeOnCompletion {
                            if (settingJob === job) settingJob = null
                        }
                        job.start()
                        job
                    }
            }
        return inFlight?.await() ?: checkNotNull(cachedSnapshot())
    }

    fun imageHost(): String? = cachedSnapshot()?.imageHost?.trimEnd('/')

    /**
     * Probes `/setting` again but only replaces the image host. The API base and its cached
     * snapshot stay untouched so an image failure never costs an extra API round trip.
     *
     * When a probe is already in flight, callers get the cached host instead of waiting: the
     * download slots must not be held hostage by a slow probe.
     */
    suspend fun refreshImageHost(): String? {
        if (elapsedMs() - lastImageHostRefreshAt < IMAGE_HOST_REFRESH_THROTTLE_MS) {
            return imageHost()
        }
        if (!imageHostLock.tryLock()) return imageHost()
        try {
            if (elapsedMs() - lastImageHostRefreshAt < IMAGE_HOST_REFRESH_THROTTLE_MS) {
                return imageHost()
            }
            lastImageHostRefreshAt = elapsedMs()
            val candidates =
                if (orderedBases.isNotEmpty()) {
                    orderedBases
                } else {
                    domainResolver.resolveCandidateDomains()
                }
            val probed =
                try {
                    val result =
                        domainResolver.probeSetting(
                            candidates = candidates,
                            timeoutMs = IMAGE_HOST_PROBE_TIMEOUT_MS,
                        )
                    orderedBases = result.orderedBases
                    persistPool()
                    result.snapshot
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ApiFailure) {
                    null
                } ?: return null
            val updated = (snapshot ?: probed).copy(imageHost = probed.imageHost)
            snapshot = updated
            settingStore?.updateImageHost(probed.imageHost)
            perf { "image_host_refresh host=${probed.imageHost.hostName()}" }
            return updated.imageHost
        } finally {
            imageHostLock.unlock()
        }
    }

    fun userAgent(): String = ApiConfig.USER_AGENT

    /**
     * Fetches a decoded API payload. The current pool is tried in order; if every base fails with
     * a failoverable error, the remote host list is refreshed and the new pool gets one retry.
     */
    suspend fun getJson(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiPayload {
        val setting = ensureSetting()
        return try {
            requestAcrossCandidates(setting, path, params)
        } catch (e: ApiFailure) {
            if (!e.canFailover) throw e
            if (!refreshPool(force = true)) throw e
            val updated = cachedSnapshot() ?: setting
            requestAcrossCandidates(updated, path, params)
        }
    }

    private suspend fun requestAcrossCandidates(
        setting: SettingSnapshot,
        path: String,
        params: Map<String, String>,
    ): ApiPayload {
        var lastFailure: ApiFailure? = null
        val candidates = buildCandidates(setting)
        candidates.forEachIndexed { index, base ->
            try {
                val ts = now()
                val value =
                    requestJson(
                        base = base,
                        path = path,
                        params = params + ("t" to "$ts"),
                        ts = ts,
                        version = setting.version,
                        callTimeoutMs = REQUEST_TIMEOUT_MS,
                    )
                if (base != setting.baseUrl) adoptBase(base, setting)
                return ApiPayload(value, setting.imageHost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiFailure) {
                lastFailure = e
                if (!e.canFailover) throw e
                dropBase(base)
                if (index < candidates.lastIndex) NetworkMetrics.recordFailover()
                perf { "api_failover from=${base.hostName()} reason=${e.message}" }
            }
        }
        throw lastFailure ?: ApiFailure.Network("no usable base")
    }

    private fun buildCandidates(setting: SettingSnapshot): List<String> =
        buildList {
            // The probed pool is more trustworthy than a snapshot base that may have gone stale.
            addAll(orderedBases)
            add(setting.baseUrl)
            addAll(ApiConfig.API_BASES)
        }.map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun adoptBase(base: String, setting: SettingSnapshot) {
        if (setting.baseUrl == base) return
        val updated = (snapshot ?: setting).copy(baseUrl = base)
        snapshot = updated
        settingStore?.write(updated)
        if (orderedBases.firstOrNull() != base) {
            orderedBases = listOf(base) + orderedBases.filterNot { it == base }
            poolStore?.write(EndpointPool(orderedBases, System.currentTimeMillis()))
        }
        perf { "api_base_adopted host=${base.hostName()}" }
    }

    private suspend fun loadSetting(): SettingSnapshot {
        val started = elapsedMs()
        val probeResult = domainResolver.probeSetting(resolveCandidates())
        orderedBases = probeResult.orderedBases
        persistPool()
        nextPoolRefreshAt = System.currentTimeMillis() + POOL_TTL_MS
        val setting = probeResult.snapshot
        snapshot = setting
        settingStore?.write(setting)
        perf {
            "setting_loaded host=${setting.baseUrl.hostName()} latency=${probeResult.latencyMs}ms " +
                "available=${probeResult.orderedBases.size} ms=${elapsedMs() - started}"
        }
        return setting
    }

    /**
     * Resolves the remote domain list and combines it with the persisted pool and compile-time
     * fallbacks. Remote entries come first so a stale cached base cannot starve a newly published
     * one during probing.
     */
    private suspend fun resolveCandidates(): List<String> {
        val remote =
            try {
                domainResolver.resolveCandidateDomains()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        return (remote + orderedBases + ApiConfig.API_BASES)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun persistPool() {
        poolStore?.write(EndpointPool(orderedBases, System.currentTimeMillis()))
    }

    private fun dropBase(base: String) {
        val normalized = base.trim().trimEnd('/')
        if (normalized !in orderedBases) return
        orderedBases = orderedBases.filterNot { it == normalized }
        poolStore?.write(EndpointPool(orderedBases, System.currentTimeMillis()))
    }

    /**
     * Re-resolves remote domains and probes the whole pool. The cached snapshot stays usable while
     * this runs; only a successful probe replaces it. Failures retry after [POOL_RETRY_MS].
     */
    private suspend fun refreshPool(force: Boolean): Boolean {
        if (!force && System.currentTimeMillis() < nextPoolRefreshAt) return false
        val waitStarted = System.currentTimeMillis()
        return poolRefreshLock.withLock {
            // If another refresh completed while this caller was waiting, reuse its result instead
            // of probing the same outage again.
            if (force && lastPoolAttemptAt > waitStarted) {
                return@withLock lastPoolSuccessAt > waitStarted
            }
            if (force &&
                lastPoolAttemptAt > lastPoolSuccessAt &&
                System.currentTimeMillis() < nextPoolRefreshAt
            ) {
                return@withLock false
            }
            if (!force && System.currentTimeMillis() < nextPoolRefreshAt) return@withLock false
            try {
                val probeResult = domainResolver.probeSetting(resolveCandidates())
                orderedBases = probeResult.orderedBases
                persistPool()
                val updated = probeResult.snapshot
                snapshot = updated
                settingStore?.write(updated)
                nextPoolRefreshAt = System.currentTimeMillis() + POOL_TTL_MS
                lastPoolAttemptAt = System.currentTimeMillis()
                lastPoolSuccessAt = lastPoolAttemptAt
                perf {
                    "pool_refreshed host=${updated.baseUrl.hostName()} " +
                        "available=${probeResult.orderedBases.size}"
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                nextPoolRefreshAt = System.currentTimeMillis() + POOL_RETRY_MS
                lastPoolAttemptAt = System.currentTimeMillis()
                false
            }
        }
    }

    private suspend fun requestJson(
        base: String,
        path: String,
        params: Map<String, String>,
        ts: Long,
        version: String,
        callTimeoutMs: Long,
    ): Any {
        val body = requestText(base, path, params, ts, version, callTimeoutMs)
        val outer =
            try {
                JSONObject(body)
            } catch (_: Exception) {
                throw ApiFailure.Decode("invalid envelope")
            }
        val code = outer.optInt("code", -1)
        if (code != 200) {
            val message =
                sequenceOf("errorMsg", "msg", "message")
                    .map { outer.optString(it) }
                    .firstOrNull { it.isNotBlank() && it != "null" }
                    .orEmpty()
            throw ApiFailure.Business(code, message)
        }
        val decoded =
            Codec.decodeJson(outer.optString("data"), ts)
                ?: throw ApiFailure.Decode("undecodable data")
        return try {
            JSONTokener(decoded).nextValue()
        } catch (_: Exception) {
            throw ApiFailure.Decode("invalid json")
        }
    }

    private suspend fun requestText(
        base: String,
        path: String,
        params: Map<String, String>,
        ts: Long,
        version: String,
        callTimeoutMs: Long,
    ): String {
        val baseUrl = "$base$path".toHttpUrlOrNull() ?: throw ApiFailure.Decode("invalid url")
        val url =
            baseUrl
                .newBuilder()
                .apply {
                    params.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("token", Codec.token(ts))
                .header("tokenparam", Codec.tokenParam(ts, version))
                .header("user-agent", ApiConfig.USER_AGENT)
                .header("accept", "*/*")
                .build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        val response =
            try {
                call.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ApiFailure.Network(e.message ?: "request failed", e)
            }
        response.use {
            if (!it.isSuccessful) throw ApiFailure.Http(it.code)
            val body = it.body ?: throw ApiFailure.Decode("empty body")
            return try {
                body.string()
            } catch (e: IOException) {
                throw ApiFailure.Network(e.message ?: "body read failed", e)
            }
        }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun String.hostName(): String = toHttpUrlOrNull()?.host ?: this

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

    private fun perf(message: () -> String) {
        try {
            if (Log.isLoggable(PERF_TAG, Log.DEBUG)) {
                Log.d(PERF_TAG, message())
            }
        } catch (_: Throwable) {
            // Android Log is not mocked when running in JVM unit tests
        }
    }

    private companion object {
        const val IMAGE_HOST_PROBE_TIMEOUT_MS = 6_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val IMAGE_HOST_REFRESH_THROTTLE_MS = 30_000L
        const val POOL_TTL_MS = 30 * 60 * 1000L
        const val POOL_RETRY_MS = 60 * 1000L
        const val PERF_TAG = "ReaderPerf"
    }
}
