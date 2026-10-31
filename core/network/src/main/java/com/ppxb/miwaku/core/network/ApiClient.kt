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

/** Read-only pool snapshot for diagnostics; reading it never triggers a probe. */
data class EndpointPoolSummary(
    val probedAt: Long,
    val apiVersion: String,
    val chosenApi: String?,
    val chosenImage: String?,
    val api: List<EndpointHealth>,
    val image: List<EndpointHealth>,
    val apiProbes: List<ProbeRecord> = emptyList(),
    val imageProbes: List<ProbeRecord> = emptyList(),
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
    /** Install-specific UA from [UserAgentStore]; the constant is only the fallback. */
    private val userAgentValue: String = ApiConfig.USER_AGENT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val domainResolver = DomainResolver(probeHttpClient, userAgentValue)
    private val settingLock = Mutex()
    private val poolRefreshLock = Mutex()
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

    fun userAgent(): String = userAgentValue

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

    /** Current pools and selection, for the settings screen. */
    fun endpointSummary(): EndpointPoolSummary {
        val current = currentState()
        return EndpointPoolSummary(
            probedAt = current.probedAt,
            apiVersion = current.apiVersion,
            chosenApi = current.chosenApi,
            chosenImage = current.chosenImage,
            api = current.api,
            image = current.image,
            apiProbes = current.apiProbes,
            imageProbes = current.imageProbes,
        )
    }

    /**
     * Returns an image host other than [failedHost], penalising the host that just failed.
     *
     * The caller is on the page-loading hot path, so this never waits for a probe: it returns the
     * best remaining host (cooling entries included, then the compile-time fallback CDN) and lets
     * the throttled pool refresh run in the background.
     */
    fun nextImageHost(failedHost: String?): String? {
        val excluded = failedHost?.trim()?.trimEnd('/')
        if (!excluded.isNullOrEmpty()) penalizeImage(excluded)
        pickImage(excluded)?.let { return it }
        scheduleImagePoolRefresh()
        return currentState().image.firstOrNull { it.url != excluded }?.url
            ?: ApiConfig.FALLBACK_IMAGE_BASES.firstOrNull { it != excluded }
    }

    private fun scheduleImagePoolRefresh() {
        if (elapsedMs() - lastImageRefreshAt < IMAGE_REFRESH_THROTTLE_MS) return
        lastImageRefreshAt = elapsedMs()
        scope.launch {
            runCatching { refreshPool(force = true) }
        }
    }

    /**
     * Fetches a decoded API payload. Candidates are tried in rank order inside one
     * [FAILOVER_BUDGET_MS] deadline; when every candidate fails with a failoverable error the pool
     * is re-probed once, but only if the deadline still allows another attempt.
     */
    suspend fun getJson(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiPayload {
        val setting = ensureSetting()
        val deadlineNs = System.nanoTime() + FAILOVER_BUDGET_MS * 1_000_000L
        return try {
            requestAcrossCandidates(setting, path, params, deadlineNs)
        } catch (e: ApiFailure) {
            if (!e.canFailover) throw e
            if (remainingMs(deadlineNs) <= MIN_ATTEMPT_MS) throw e
            if (!refreshPool(force = true)) throw e
            val updated = currentState().toSnapshot() ?: throw e
            requestAcrossCandidates(updated, path, params, deadlineNs)
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
        deadlineNs: Long,
    ): ApiPayload {
        var lastFailure: ApiFailure? = null
        val candidates = candidateBases(setting)
        for ((index, base) in candidates.withIndex()) {
            val remaining = remainingMs(deadlineNs)
            if (remaining <= MIN_ATTEMPT_MS) break
            val attemptCap = if (index == 0) REQUEST_TIMEOUT_MS else FAILOVER_ATTEMPT_TIMEOUT_MS
            try {
                val ts = now()
                val value =
                    requestJson(
                        base = base,
                        path = path,
                        params = params + ("t" to "$ts"),
                        ts = ts,
                        version = setting.version,
                        callTimeoutMs = minOf(attemptCap, remaining),
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

    private fun remainingMs(deadlineNs: Long): Long = (deadlineNs - System.nanoTime()) / 1_000_000L

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
            if (current.chosenApi == normalized && current.api.firstOrNull()?.url == normalized) {
                current
            } else {
                // Keep the probed latency/health of the entry instead of replacing it with a blank one.
                val entry = current.api.firstOrNull { it.url == normalized } ?: EndpointHealth(normalized)
                val rest = current.api.filterNot { it.url == normalized }
                current.copy(
                    chosenApi = normalized,
                    api = (listOf(entry) + rest).take(EndpointState.MAX_API_POOL),
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
        val suspicious = failure.isSuspicious
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

    /**
     * Probes every image host announced by the reachable endpoints, keeping the previous ones.
     * Dead results are returned as [ProbeRecord]s only, so diagnostics can show why a host vanished.
     */
    private suspend fun probeImagePool(probe: ProbeResult): ImagePoolResult {
        val now = System.currentTimeMillis()
        val previous = currentState().image
        val previousByHost = previous.associateBy { it.url }
        val candidates =
            (probe.probes.mapNotNull { it.snapshot?.imageHost } + probe.snapshot.imageHost + previous.map { it.url })
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(DomainResolver.MAX_IMAGE_HOSTS)

        val results = domainResolver.probeImageHosts(candidates)
        val probed =
            results.mapNotNull { result ->
                if (result.status == EndpointStatus.Dead) return@mapNotNull null
                val base = previousByHost[result.host] ?: EndpointHealth(result.host)
                val ranked = base.copy(latencyMs = result.latencyMs)
                if (result.status == EndpointStatus.Ok) {
                    ranked.copy(failures = 0, cooldownUntil = 0L, lastSuccessAt = now)
                } else {
                    ranked
                }
            }
        val records =
            results.map {
                ProbeRecord(it.host, it.status, it.latencyMs, it.httpStatus, it.reason)
            }

        if (probed.isNotEmpty()) {
            return ImagePoolResult(probed.take(EndpointState.MAX_IMAGE_POOL), records)
        }
        // Every placeholder probe failed: the reference fallback CDN is the last resort, exactly as
        // the plugin treats JM_FALLBACK_IMAGE_BASE when its own image pool comes back empty.
        return ImagePoolResult(listOf(EndpointHealth(ApiConfig.FALLBACK_IMAGE_BASES.first())), records)
    }

    private data class ImagePoolResult(
        val pool: List<EndpointHealth>,
        val probes: List<ProbeRecord>,
    )

    private fun buildState(probe: ProbeResult, image: ImagePoolResult): EndpointState {
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
            image = image.pool,
            chosenApi = probe.snapshot.baseUrl,
            chosenImage = image.pool.firstOrNull()?.url ?: probe.snapshot.imageHost,
            apiProbes =
                probe.probes.map { ProbeRecord(it.baseUrl, it.status, it.latencyMs, it.httpStatus, it.reason) },
            imageProbes = image.probes,
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
        return when (val envelope = Codec.decodeEnvelope(body, ts)) {
            is Codec.Envelope.Payload ->
                try {
                    JSONTokener(envelope.json).nextValue()
                } catch (_: Exception) {
                    throw ApiFailure.Decode("invalid json")
                }
            is Codec.Envelope.Business -> throw ApiFailure.Business(envelope.code, envelope.message)
            is Codec.Envelope.Invalid -> throw ApiFailure.Decode(envelope.reason)
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
                .headers(RequestHeaders.api(ts, version, userAgentValue))
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
            if (!it.isSuccessful) {
                // Keep the editor/CDN text: "HTTP 520" alone tells the user nothing.
                val text = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                throw ApiFailure.Http(it.code, Codec.serverMessage(text, ts))
            }
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
        const val FAILOVER_ATTEMPT_TIMEOUT_MS = 10_000L
        const val FAILOVER_BUDGET_MS = 30_000L
        const val MIN_ATTEMPT_MS = 1_000L
        const val IMAGE_REFRESH_THROTTLE_MS = 30_000L
        const val POOL_TTL_MS = 30 * 60 * 1000L
        const val POOL_RETRY_MS = 60 * 1000L
        const val PERF_TAG = "ReaderPerf"
    }
}
