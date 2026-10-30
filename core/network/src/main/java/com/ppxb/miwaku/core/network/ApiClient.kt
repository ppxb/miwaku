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
 * Responsibilities kept here on purpose: the endpoint state (pools, health and selection), the
 * failure classification policy, and failover. Interceptors only handle transport concerns.
 *
 * Failures never remove an endpoint from the pool: they lower its rank and start a cooldown
 * ([EndpointHealth]), so one WAF page cannot drop a healthy host for half an hour.
 */
class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val stateStore: EndpointStateStore? = null,
    /**
     * Client used for health probes. It must not share the business rate limiter or retry layer,
     * otherwise a queued probe reports its own queueing delay as endpoint latency and can starve
     * its 6s call timeout, which would mark a healthy endpoint dead.
     */
    probeHttpClient: OkHttpClient = httpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domainResolver = DomainResolver(probeHttpClient)
    private val settingLock = Mutex()
    private val poolRefreshLock = Mutex()
    private val imageRefreshLock = Mutex()
    private val stateLock = Any()

    @Volatile
    private var state: EndpointState? = null

    @Volatile
    private var nextPoolRefreshAt = 0L

    @Volatile
    private var poolRefreshJob: Job? = null

    @Volatile
    private var settingJob: Deferred<SettingSnapshot>? = null

    @Volatile
    private var lastPoolAttemptAt = 0L

    @Volatile
    private var lastPoolSuccessAt = 0L

    @Volatile
    private var lastImageRefreshAt = 0L

    fun userAgent(): String = ApiConfig.USER_AGENT

    /**
     * Returns the current selection, probing `/setting` at most once for concurrent callers.
     * Never holds a lock while doing network I/O.
     */
    suspend fun ensureSetting(): SettingSnapshot {
        currentState().toSnapshot()?.let {
            schedulePoolRefreshIfNeeded()
            return it
        }
        val inFlight =
            settingLock.withLock {
                currentState().toSnapshot()?.let { return@withLock null }
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
        return inFlight?.await() ?: checkNotNull(currentState().toSnapshot())
    }

    fun imageHost(): String? = currentState().chosenImage

    /**
     * Returns an image host other than [failedHost], penalising the host that just failed.
     * The pool is re-probed at most once per [IMAGE_REFRESH_THROTTLE_MS]; a slow probe never
     * blocks the caller, which falls back to the least-failed remaining host and finally to the
     * compile-time fallback CDN.
     */
    suspend fun nextImageHost(failedHost: String?): String? {
        val excluded = failedHost?.trim()?.trimEnd('/')
        if (!excluded.isNullOrEmpty()) penalizeImage(excluded)
        pickImage(excluded)?.let { return it }

        if (elapsedMs() - lastImageRefreshAt >= IMAGE_REFRESH_THROTTLE_MS && imageRefreshLock.tryLock()) {
            try {
                lastImageRefreshAt = elapsedMs()
                refreshPool(force = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep serving from the stale pool.
            } finally {
                imageRefreshLock.unlock()
            }
            pickImage(excluded)?.let { return it }
        }
        return currentState().image.firstOrNull { it.url != excluded }?.url
            ?: ApiConfig.FALLBACK_IMAGE_BASES.firstOrNull { it != excluded }
    }

    /**
     * Fetches a decoded API payload. The pool is tried in rank order; if every candidate fails
     * with a failoverable error, the pool is re-probed once and the request retried.
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
            val updated = currentState().toSnapshot() ?: throw e
            requestAcrossCandidates(updated, path, params)
        }
    }

    private fun currentState(): EndpointState =
        state ?: synchronized(stateLock) { state ?: loadStateLocked() }

    private fun loadStateLocked(): EndpointState {
        val loaded = runCatching { stateStore?.read() }.getOrNull() ?: EndpointState()
        state = loaded
        nextPoolRefreshAt =
            when {
                loaded.probedAt > 0L -> loaded.probedAt + POOL_TTL_MS
                stateStore == null -> Long.MAX_VALUE
                else -> 0L
            }
        return loaded
    }

    private fun updateState(transform: (EndpointState) -> EndpointState): EndpointState =
        synchronized(stateLock) {
            val current = state ?: loadStateLocked()
            val next = transform(current)
            if (next != current) {
                state = next
                runCatching { stateStore?.write(next) }
            }
            next
        }

    /**
     * Refreshes the persisted pool in the background when it is missing or older than
     * [POOL_TTL_MS]. The cached selection keeps the first request instant.
     */
    private fun schedulePoolRefreshIfNeeded() {
        if (System.currentTimeMillis() < nextPoolRefreshAt) return
        if (poolRefreshJob?.isActive == true) return
        poolRefreshJob = scope.launch { refreshPool(force = false) }
    }

    private suspend fun requestAcrossCandidates(
        setting: SettingSnapshot,
        path: String,
        params: Map<String, String>,
    ): ApiPayload {
        var lastFailure: ApiFailure? = null
        val candidates = candidateBases(setting)
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
                reward(base)
                if (base != setting.baseUrl) adoptBase(base)
                return ApiPayload(value, setting.imageHost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiFailure) {
                lastFailure = e
                if (!e.canFailover) throw e
                penalize(base, e)
                if (index < candidates.lastIndex) NetworkMetrics.recordFailover()
                perf { "api_failover from=${base.hostName()} reason=${e.message}" }
            }
        }
        throw lastFailure ?: ApiFailure.Network("no usable base")
    }

    private fun candidateBases(setting: SettingSnapshot): List<String> {
        val ranking = currentState().rankedApi(System.currentTimeMillis()).map { it.url }
        // Cooling entries stay in the list as a last resort: a degraded base still beats nothing.
        return (ranking + setting.baseUrl + ApiConfig.API_BASES)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun adoptBase(base: String) {
        val normalized = base.trim().trimEnd('/')
        updateState { current ->
            if (current.chosenApi == normalized && current.api.firstOrNull()?.url == normalized) current
            else {
                val rest = current.api.filterNot { it.url == normalized }
                current.copy(
                    chosenApi = normalized,
                    api = (listOf(EndpointHealth(normalized)) + rest).take(EndpointState.MAX_API_POOL),
                )
            }
        }
        perf { "api_base_adopted host=${base.hostName()}" }
    }

    /** Clears a penalty only when the endpoint actually carried one, so healthy calls stay write-free. */
    private fun reward(base: String) {
        val normalized = base.trim().trimEnd('/')
        val entry = currentState().api.firstOrNull { it.url == normalized } ?: return
        if (entry.failures == 0 && entry.cooldownUntil == 0L) return
        val now = System.currentTimeMillis()
        updateState { current ->
            current.copy(api = current.api.map { if (it.url == normalized) it.recovered(now) else it })
        }
    }

    private fun penalize(base: String, failure: ApiFailure) {
        val normalized = base.trim().trimEnd('/')
        val now = System.currentTimeMillis()
        val suspicious = failure is ApiFailure.Decode
        updateState { current ->
            val existing = current.api.firstOrNull { it.url == normalized } ?: EndpointHealth(normalized)
            val punished = existing.punished(now, suspicious)
            val pool =
                if (current.api.any { it.url == normalized }) {
                    current.api.map { if (it.url == normalized) punished else it }
                } else {
                    current.api + punished
                }
            current.copy(api = pool.take(EndpointState.MAX_API_POOL))
        }
        perf { "api_penalized host=${base.hostName()} failures=${failure::class.simpleName}" }
    }

    private fun penalizeImage(host: String) {
        val normalized = host.trim().trimEnd('/')
        val now = System.currentTimeMillis()
        updateState { current ->
            if (current.image.none { it.url == normalized }) current
            else {
                current.copy(
                    image = current.image.map { if (it.url == normalized) it.punished(now, suspicious = false) else it },
                )
            }
        }
    }

    private fun pickImage(excluded: String?): String? {
        val now = System.currentTimeMillis()
        return currentState().rankedImage(now).firstOrNull { it.url != excluded && !it.isCoolingDown(now) }?.url
    }

    private suspend fun loadSetting(): SettingSnapshot {
        val started = elapsedMs()
        val probe = probeCandidates()
        val next = buildState(probe, probeImagePool(probe))
        updateState { next }
        nextPoolRefreshAt = System.currentTimeMillis() + POOL_TTL_MS
        lastPoolAttemptAt = System.currentTimeMillis()
        lastPoolSuccessAt = lastPoolAttemptAt
        perf {
            "setting_loaded host=${probe.snapshot.baseUrl.hostName()} latency=${probe.latencyMs}ms " +
                "available=${probe.orderedBases.size} image=${next.image.size} ms=${elapsedMs() - started}"
        }
        return checkNotNull(next.toSnapshot())
    }

    /**
     * Resolves the remote domain list and combines it with the persisted pool. Remote entries come
     * first so a stale cached base cannot starve a newly published one during probing.
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
        val pool = currentState().api.map { it.url }
        return (remote + pool)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Probes published + persisted bases first; the compile-time fallbacks cost one extra round
     * only when nothing usable answered, so they never extend the normal cold-start probe tail.
     */
    private suspend fun probeCandidates(): ProbeResult {
        val candidates = resolveCandidates()
        return try {
            domainResolver.probeSetting(candidates)
        } catch (e: ApiFailure) {
            val extended = (candidates + ApiConfig.API_BASES).distinct()
            // When every config source failed, [resolveCandidateDomains] already returned the
            // fallbacks; probing them again would only burn another timeout.
            if (extended.size == candidates.size) throw e
            perf { "probe_fallback_bases added=${extended.size - candidates.size}" }
            domainResolver.probeSetting(extended)
        }
    }

    /**
     * Re-resolves remote domains and probes the whole pool. The cached selection stays usable while
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
                val probe = probeCandidates()
                val next = buildState(probe, probeImagePool(probe))
                updateState { next }
                nextPoolRefreshAt = System.currentTimeMillis() + POOL_TTL_MS
                lastPoolAttemptAt = System.currentTimeMillis()
                lastPoolSuccessAt = lastPoolAttemptAt
                perf {
                    "pool_refreshed host=${probe.snapshot.baseUrl.hostName()} " +
                        "available=${probe.orderedBases.size} image=${next.image.size}"
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

    /** Probes every image host announced by the reachable endpoints, keeping the previous ones. */
    private suspend fun probeImagePool(probe: ProbeResult): List<EndpointHealth> {
        val now = System.currentTimeMillis()
        val previous = currentState().image
        val previousByHost = previous.associateBy { it.url }
        val candidates =
            (probe.probes.mapNotNull { it.snapshot?.imageHost } + probe.snapshot.imageHost + previous.map { it.url })
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(DomainResolver.MAX_IMAGE_HOSTS)

        val probed =
            domainResolver.probeImageHosts(candidates).mapNotNull { result ->
                if (result.status == EndpointStatus.Dead) return@mapNotNull null
                val base = previousByHost[result.host] ?: EndpointHealth(result.host)
                val ranked = base.copy(latencyMs = result.latencyMs)
                if (result.status == EndpointStatus.Ok) {
                    ranked.copy(failures = 0, cooldownUntil = 0L, lastSuccessAt = now)
                } else {
                    ranked
                }
            }

        if (probed.isNotEmpty()) return probed.take(EndpointState.MAX_IMAGE_POOL)
        // Every placeholder probe failed: the reference fallback CDN is the last resort, exactly as
        // the plugin treats JM_FALLBACK_IMAGE_BASE when its own image pool comes back empty.
        return listOf(EndpointHealth(ApiConfig.FALLBACK_IMAGE_BASES.first()))
    }

    private fun buildState(probe: ProbeResult, image: List<EndpointHealth>): EndpointState {
        val now = System.currentTimeMillis()
        val previous = currentState()
        val previousByUrl = previous.api.associateBy { it.url }
        val probesByUrl = probe.probes.associateBy { it.baseUrl }
        val api =
            probe.orderedBases
                .mapNotNull { url ->
                    val result = probesByUrl[url] ?: return@mapNotNull null
                    val base = previousByUrl[url] ?: EndpointHealth(url)
                    val ranked = base.copy(latencyMs = result.latencyMs)
                    if (result.status == EndpointStatus.Ok) {
                        ranked.copy(failures = 0, cooldownUntil = 0L, lastSuccessAt = now)
                    } else {
                        ranked
                    }
                }
                .take(EndpointState.MAX_API_POOL)
        return EndpointState(
            probedAt = now,
            apiVersion = probe.snapshot.version,
            api = api,
            image = image,
            chosenApi = probe.snapshot.baseUrl,
            chosenImage = image.firstOrNull()?.url ?: probe.snapshot.imageHost,
        )
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
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val IMAGE_REFRESH_THROTTLE_MS = 30_000L
        const val POOL_TTL_MS = 30 * 60 * 1000L
        const val POOL_RETRY_MS = 60 * 1000L
        const val PERF_TAG = "ReaderPerf"
    }
}
