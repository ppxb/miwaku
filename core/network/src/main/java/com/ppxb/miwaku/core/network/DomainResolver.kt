package com.ppxb.miwaku.core.network

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class EndpointStatus { Ok, Degraded, Dead }

/**
 * One `/setting` probe result. Ok and Degraded entries are eligible for the pool; Dead entries
 * are kept only for diagnostics. A Degraded entry deliberately has no [snapshot]: it responded,
 * but did not produce a usable /setting payload (WAF page, decrypt failure, 5xx, ...).
 */
data class EndpointProbe(
    val baseUrl: String,
    val status: EndpointStatus,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val reason: String? = null,
    val snapshot: SettingSnapshot? = null,
)

data class ProbeResult(
    val snapshot: SettingSnapshot,
    val orderedBases: List<String>,
    val probes: List<EndpointProbe>,
    val latencyMs: Long,
)

/** Result of probing one image CDN host with the static placeholder asset. */
data class ImageHostProbe(
    val host: String,
    val status: EndpointStatus,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val reason: String? = null,
)

/**
 * Domain resolver responsible for:
 * 1. Fetching remote dynamic domains from Bytepluses encrypted sources concurrently.
 * 2. Probing candidate base URLs against /setting concurrently (bounded to 6).
 * 3. Ranking ok before degraded, each by actual response latency, and dropping dead entries.
 */
internal class DomainResolver(
    private val httpClient: OkHttpClient,
    private val userAgent: String = ApiConfig.USER_AGENT,
) {
    /**
     * Concurrently queries Bytepluses host config URLs; the first one that successfully decrypts
     * and produces valid HTTPS endpoints wins, and all other in-flight requests are cancelled.
     * Falls back to [fallbacks] if all config sources fail or time out.
     */
    suspend fun resolveCandidateDomains(
        sources: List<String> = ApiConfig.DOMAIN_CONFIG_URLS,
        fallbacks: List<String> = ApiConfig.API_BASES,
        timeoutMs: Long = REMOTE_CONFIG_TIMEOUT_MS,
    ): List<String> {
        val remote = fetchRemoteDomains(sources, timeoutMs)
        return if (!remote.isNullOrEmpty()) remote else fallbacks
    }

    /**
     * Probes candidate base URLs by hitting /setting concurrently (bounded to [concurrency]).
     * Returns the fastest Ok endpoint's snapshot; degraded endpoints stay in [ProbeResult.orderedBases]
     * after every Ok endpoint, while dead endpoints are excluded entirely.
     */
    suspend fun probeSetting(
        candidates: List<String>,
        concurrency: Int = DEFAULT_PROBE_CONCURRENCY,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): ProbeResult {
        val unique = candidates.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) {
            throw ApiFailure.Network("No candidate domains provided")
        }
        val semaphore = Semaphore(concurrency)
        val probes =
            coroutineScope {
                unique.map { base ->
                    async {
                        semaphore.withPermit {
                            probeSingleSetting(base, timeoutMs)
                        }
                    }
                }.awaitAll()
            }

        val ranked =
            probes
                .filter { it.status != EndpointStatus.Dead && it.baseUrl.isNotEmpty() }
                .sortedWith(compareBy({ rank(it.status) }, { it.latencyMs }))
        val winner = ranked.firstOrNull { it.status == EndpointStatus.Ok && it.snapshot != null }
        if (winner == null) {
            val summary =
                probes.joinToString("; ") { probe ->
                    val detail = probe.reason?.let { " ($it)" }.orEmpty()
                    "${probe.baseUrl.hostName()}: ${probe.status}$detail"
                }
            throw ApiFailure.Network(summary.ifEmpty { "setting unavailable" })
        }
        val orderedBases = ranked.map { it.baseUrl }
        perf {
            "probed_winner host=${winner.baseUrl.hostName()} latency=${winner.latencyMs}ms " +
                "ok=${ranked.count { it.status == EndpointStatus.Ok }} " +
                "degraded=${ranked.count { it.status == EndpointStatus.Degraded }} " +
                "dead=${probes.count { it.status == EndpointStatus.Dead }}"
        }
        return ProbeResult(
            snapshot = checkNotNull(winner.snapshot),
            orderedBases = orderedBases,
            probes = probes,
            latencyMs = winner.latencyMs,
        )
    }

    /**
     * Probes image hosts with the CDN's static placeholder. "Dead" only means the host did not
     * answer at all; a non-2xx answer stays in the pool as degraded, because a placeholder 404
     * does not imply that real pages fail on that host.
     */
    suspend fun probeImageHosts(
        hosts: List<String>,
        concurrency: Int = IMAGE_PROBE_CONCURRENCY,
        timeoutMs: Long = IMAGE_PROBE_TIMEOUT_MS,
    ): List<ImageHostProbe> {
        val unique =
            hosts.map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_IMAGE_HOSTS)
        if (unique.isEmpty()) return emptyList()
        val semaphore = Semaphore(concurrency)
        return coroutineScope {
                unique.map { host ->
                    async { semaphore.withPermit { probeSingleImage(host, timeoutMs) } }
                }.awaitAll()
            }
            .sortedWith(compareBy({ rank(it.status) }, { it.latencyMs }))
    }

    private suspend fun probeSingleImage(host: String, timeoutMs: Long): ImageHostProbe {
        val start = System.nanoTime()
        return try {
            val url =
                "$host$PLACEHOLDER_PATH".toHttpUrlOrNull()
                    ?: return ImageHostProbe(host, EndpointStatus.Dead, 0L, reason = "invalid url")
            val request =
                Request.Builder()
                    .url(url)
                    .headers(RequestHeaders.image(userAgent))
                    .build()
            val call = httpClient.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = call.await()
            val latency = (System.nanoTime() - start) / 1_000_000L
            response.use { resp ->
                val bytes = resp.body?.bytes()?.size ?: 0
                when {
                    !resp.isSuccessful ->
                        ImageHostProbe(host, EndpointStatus.Degraded, latency, resp.code, "HTTP ${resp.code}")
                    bytes <= 0 ->
                        ImageHostProbe(host, EndpointStatus.Degraded, latency, resp.code, "empty placeholder")
                    else -> ImageHostProbe(host, EndpointStatus.Ok, latency, resp.code)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ImageHostProbe(
                host,
                EndpointStatus.Dead,
                (System.nanoTime() - start) / 1_000_000L,
                reason = e.message ?: "network error",
            )
        }
    }

    private fun rank(status: EndpointStatus): Int =
        when (status) {
            EndpointStatus.Ok -> 0
            EndpointStatus.Degraded -> 1
            EndpointStatus.Dead -> 2
        }

    private suspend fun fetchRemoteDomains(
        sources: List<String>,
        timeoutMs: Long,
    ): List<String>? =
        coroutineScope {
            if (sources.isEmpty()) return@coroutineScope null
            val result = CompletableDeferred<List<String>>()
            val failureCount = AtomicInteger(0)
            val jobs =
                sources.map { url ->
                    async {
                        try {
                            val domains = fetchSingleHostConfig(url, timeoutMs)
                            if (!domains.isNullOrEmpty()) {
                                result.complete(domains)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        } finally {
                            if (failureCount.incrementAndGet() == sources.size) {
                                result.complete(emptyList())
                            }
                        }
                    }
                }

            val won =
                try {
                    withTimeoutOrNull(timeoutMs) {
                        result.await()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            jobs.forEach { it.cancel() }
            won?.ifEmpty { null }
        }

    private suspend fun fetchSingleHostConfig(url: String, timeoutMs: Long): List<String>? {
        val request =
            Request.Builder()
                .url(url)
                .headers(RequestHeaders.config(userAgent))
                .build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val response =
            try {
                call.await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null
            }
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string() ?: return null
            return Codec.decodeHostConfig(body)
        }
    }

    private suspend fun probeSingleSetting(base: String, timeoutMs: Long): EndpointProbe {
        val ts = System.currentTimeMillis() / 1000L
        val start = System.nanoTime()
        return try {
            val url =
                "$base/setting?app_img_shunt=1&t=$ts".toHttpUrlOrNull()
                    ?: return EndpointProbe(
                        baseUrl = base,
                        status = EndpointStatus.Dead,
                        latencyMs = 0L,
                        reason = "invalid url",
                    )
            val request =
                Request.Builder()
                    .url(url)
                    .headers(RequestHeaders.api(ts, ApiConfig.APP_VERSION, userAgent))
                    .build()
            val call = httpClient.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = call.await()
            val latency = (System.nanoTime() - start) / 1_000_000L
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return EndpointProbe(
                        baseUrl = base,
                        status = EndpointStatus.Degraded,
                        latencyMs = latency,
                        httpStatus = resp.code,
                        reason = "HTTP ${resp.code}",
                    )
                }
                val body =
                    resp.body?.string()
                        ?: return EndpointProbe(
                            baseUrl = base,
                            status = EndpointStatus.Degraded,
                            latencyMs = latency,
                            httpStatus = resp.code,
                            reason = "empty body",
                        )
                val payload =
                    when (val envelope = Codec.decodeEnvelope(body, ts)) {
                        is Codec.Envelope.Business ->
                            return EndpointProbe(
                                baseUrl = base,
                                status = EndpointStatus.Degraded,
                                latencyMs = latency,
                                httpStatus = resp.code,
                                reason = "code ${envelope.code}: ${envelope.message}",
                            )
                        is Codec.Envelope.Invalid ->
                            return EndpointProbe(
                                baseUrl = base,
                                status = EndpointStatus.Degraded,
                                latencyMs = latency,
                                httpStatus = resp.code,
                                reason = envelope.reason,
                            )
                        is Codec.Envelope.Payload -> envelope.json
                    }
                val setting =
                    runCatching { JSONObject(payload) }.getOrNull()
                        ?: return EndpointProbe(
                            baseUrl = base,
                            status = EndpointStatus.Degraded,
                            latencyMs = latency,
                            httpStatus = resp.code,
                            reason = "invalid setting json",
                        )
                val version = setting.optString("jm3_version").trim()
                val imageHost = setting.optString("img_host").trim().trimEnd('/')
                if (version.isEmpty() || version == "null" || imageHost.isEmpty() || imageHost == "null") {
                    return EndpointProbe(
                        baseUrl = base,
                        status = EndpointStatus.Degraded,
                        latencyMs = latency,
                        httpStatus = resp.code,
                        reason = "missing jm3_version/img_host",
                    )
                }
                EndpointProbe(
                    baseUrl = base,
                    status = EndpointStatus.Ok,
                    latencyMs = latency,
                    httpStatus = resp.code,
                    snapshot = SettingSnapshot(base, version, imageHost),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val latency = (System.nanoTime() - start) / 1_000_000L
            EndpointProbe(
                baseUrl = base,
                status = EndpointStatus.Dead,
                latencyMs = latency,
                reason = e.message ?: "network error",
            )
        }
    }

    private fun String.hostName(): String = toHttpUrlOrNull()?.host ?: this

    private fun perf(message: () -> String) {
        try {
            if (Log.isLoggable(PERF_TAG, Log.DEBUG)) {
                Log.d(PERF_TAG, message())
            }
        } catch (_: Throwable) {
            // Android Log is not mocked when running in JVM unit tests
        }
    }

    companion object {
        const val DEFAULT_PROBE_CONCURRENCY = 6
        const val PROBE_TIMEOUT_MS = 6_000L
        const val REMOTE_CONFIG_TIMEOUT_MS = 5_000L
        const val IMAGE_PROBE_CONCURRENCY = 4
        const val IMAGE_PROBE_TIMEOUT_MS = 5_000L
        const val MAX_IMAGE_HOSTS = 4
        private const val PERF_TAG = "ReaderPerf"

        /** Static placeholder every image CDN serves; a full body proves the host is usable. */
        private const val PLACEHOLDER_PATH = "/media/users/nopic-.gif"
    }
}
