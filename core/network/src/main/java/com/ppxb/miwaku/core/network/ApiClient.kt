package com.ppxb.miwaku.core.network

import android.util.Log
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domainResolver = DomainResolver(httpClient)
    private val settingLock = Mutex()
    private val imageHostLock = Mutex()

    @Volatile
    private var snapshot: SettingSnapshot? = null

    @Volatile
    private var orderedBases: List<String> = emptyList()

    @Volatile
    private var cacheLoaded = false

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
        return snapshot
    }

    /**
     * Returns the current snapshot, fetching `/setting` at most once for concurrent callers.
     * Unlike the previous implementation this never holds a lock while doing network I/O.
     */
    suspend fun ensureSetting(): SettingSnapshot {
        cachedSnapshot()?.let { return it }
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
                    domainResolver.probeSetting(
                        candidates = candidates,
                        timeoutMs = IMAGE_HOST_PROBE_TIMEOUT_MS,
                    ).snapshot
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
     * Fetches a decoded API payload. A request is attempted on the current base plus one fallback;
     * only failures whose [ApiFailure.canFailover] is true move on to the next base.
     */
    suspend fun getJson(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiPayload {
        val setting = ensureSetting()
        val candidates =
            buildList {
                add(setting.baseUrl)
                orderedBases.filter { it != setting.baseUrl }.forEach(::add)
                ApiConfig.API_BASES.filter { it !in this }.forEach(::add)
            }
        var lastFailure: ApiFailure? = null
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
                val hasNext = e.canFailover && index < candidates.lastIndex
                if (!hasNext) throw e
                NetworkMetrics.recordFailover()
                perf { "api_failover from=${base.hostName()} reason=${e.message}" }
            }
        }
        throw lastFailure ?: ApiFailure.Network("no usable base")
    }

    private fun adoptBase(base: String, setting: SettingSnapshot) {
        if (setting.baseUrl == base) return
        val updated = (snapshot ?: setting).copy(baseUrl = base)
        snapshot = updated
        settingStore?.write(updated)
        perf { "api_base_adopted host=${base.hostName()}" }
    }

    private suspend fun loadSetting(): SettingSnapshot {
        val started = elapsedMs()
        val candidates = domainResolver.resolveCandidateDomains()
        val probeResult = domainResolver.probeSetting(candidates)
        val setting = probeResult.snapshot
        orderedBases = probeResult.orderedBases
        snapshot = setting
        settingStore?.write(setting)
        perf {
            "setting_loaded host=${setting.baseUrl.hostName()} latency=${probeResult.latencyMs}ms available=${probeResult.orderedBases.size} ms=${elapsedMs() - started}"
        }
        return setting
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
        const val PERF_TAG = "ReaderPerf"
    }
}
