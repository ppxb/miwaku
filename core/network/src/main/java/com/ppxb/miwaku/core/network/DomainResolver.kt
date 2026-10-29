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

/** What one successful `/setting` probe produced. */
internal data class SettingPayload(
    val version: String,
    val imageHost: String,
)

/**
 * One `/setting` probe result. Ok and Degraded entries are eligible for the pool; Dead entries
 * are kept only for diagnostics. A Degraded entry deliberately has no [setting]: it responded,
 * but did not produce a usable /setting payload (WAF page, decrypt failure, 5xx, ...).
 */
internal data class EndpointProbe(
    val baseUrl: String,
    val status: EndpointStatus,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val reason: String? = null,
    val setting: SettingPayload? = null,
)

internal data class ProbeResult(
    val winner: EndpointProbe,
    val orderedBases: List<String>,
    val probes: List<EndpointProbe>,
)

private data class ImageProbe(
    val baseUrl: String,
    val latencyMs: Long,
)

/** Everything the first request needs, without waiting for the image hosts to be probed. */
internal data class ApiResolution(
    val apiBaseUrl: String,
    val version: String,
    val hostPool: List<String>,
    /** Unprobed `img_host`s from every ok probe, the winner's first. */
    val imageCandidates: List<String>,
)

/**
 * Domain resolver responsible for:
 * 1. Fetching remote dynamic domains from Bytepluses encrypted sources concurrently.
 * 2. Probing candidate base URLs against /setting concurrently (bounded to 6).
 * 3. Ranking ok before degraded, each by actual response latency, and dropping dead entries.
 * 4. Probing the `img_host` of every ok probe against a real placeholder image so a dead CDN never
 *    reaches the reader. This runs after the API base is already usable — it must not delay the
 *    first request.
 */
internal class DomainResolver(
    private val httpClient: OkHttpClient,
) {
    /** Resolves the API base and collects image candidates. The image pool is ordered separately. */
    suspend fun resolveApiBase(): ApiResolution {
        val result = probeSetting(resolveCandidateDomains())
        val setting = checkNotNull(result.winner.setting)
        val candidates =
            buildList {
                add(setting.imageHost)
                result.probes.mapNotNullTo(this) { it.setting?.imageHost }
            }.distinct()
        return ApiResolution(
            apiBaseUrl = result.winner.baseUrl,
            version = setting.version,
            hostPool = result.orderedBases,
            imageCandidates = candidates,
        )
    }

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
     * Returns the fastest Ok endpoint as [ProbeResult.winner]; degraded endpoints stay in
     * [ProbeResult.orderedBases] after every Ok endpoint, while dead endpoints are excluded.
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
        val winner = ranked.firstOrNull { it.status == EndpointStatus.Ok && it.setting != null }
        if (winner == null) {
            val summary =
                probes.joinToString("; ") { probe ->
                    val detail = probe.reason?.let { " ($it)" }.orEmpty()
                    "${probe.baseUrl.hostName()}: ${probe.status}$detail"
                }
            throw ApiFailure.Network(summary.ifEmpty { "setting unavailable" })
        }
        perf {
            "probed_winner host=${winner.baseUrl.hostName()} latency=${winner.latencyMs}ms " +
                "ok=${ranked.count { it.status == EndpointStatus.Ok }} " +
                "degraded=${ranked.count { it.status == EndpointStatus.Degraded }} " +
                "dead=${probes.count { it.status == EndpointStatus.Dead }}"
        }
        return ProbeResult(
            winner = winner,
            orderedBases = ranked.map { it.baseUrl },
            probes = probes,
        )
    }

    /**
     * Probes each candidate image host against a real placeholder image and keeps the reachable
     * ones ordered by latency. Only `ok` enters the pool: a CDN that answers with an error page is
     * worse than no entry at all.
     */
    suspend fun orderImagePool(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val semaphore = Semaphore(DEFAULT_PROBE_CONCURRENCY)
        val probes =
            coroutineScope {
                candidates.map { base ->
                    async { semaphore.withPermit { probeImageHost(base) } }
                }.awaitAll()
            }
        val ordered = probes.filterNotNull().sortedBy { it.latencyMs }.map { it.baseUrl }
        perf { "image_pool ok=${ordered.size} candidates=${candidates.size}" }
        return ordered
    }

    private suspend fun probeImageHost(base: String): ImageProbe? {
        val url = "$base${ApiConfig.IMAGE_PROBE_PATH}".toHttpUrlOrNull() ?: return null
        val started = System.nanoTime()
        return try {
            val call =
                httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("user-agent", ApiConfig.USER_AGENT)
                        .build(),
                )
            call.timeout().timeout(IMAGE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.await().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_PROBE_BYTES) return null
                if (body.bytes().isEmpty()) return null
                ImageProbe(base, (System.nanoTime() - started) / 1_000_000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
                .header("user-agent", ApiConfig.USER_AGENT)
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
                    .header("token", Codec.token(ts))
                    .header("tokenparam", Codec.tokenParam(ts, ApiConfig.APP_VERSION))
                    .header("user-agent", ApiConfig.USER_AGENT)
                    .header("accept", "*/*")
                    .build()
            val call = httpClient.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.await().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                if (!response.isSuccessful) {
                    return degraded(base, latency, response.code, "HTTP ${response.code}")
                }
                val body =
                    response.body?.string()
                        ?: return degraded(base, latency, response.code, "empty body")
                // The envelope and its payload are parsed with the same codec the API client uses;
                // regex-scraping the JSON broke as soon as a field arrived escaped or reordered.
                val envelope =
                    runCatching { JSONObject(body) }.getOrNull()
                        ?: return degraded(base, latency, response.code, "invalid envelope")
                val code = envelope.optInt("code", -1)
                if (code != 200) {
                    val message = envelope.text("errorMsg").ifBlank { "code $code" }
                    return degraded(base, latency, response.code, message)
                }
                val decoded =
                    Codec.decodeJson(envelope.optString("data"), ts)
                        ?: return degraded(base, latency, response.code, "decrypt failed")
                val payload =
                    runCatching { JSONObject(decoded) }.getOrNull()
                        ?: return degraded(base, latency, response.code, "invalid payload")
                val version = payload.text("jm3_version")
                val imageHost = payload.text("img_host").trimEnd('/')
                if (version.isEmpty() || imageHost.isEmpty()) {
                    return degraded(base, latency, response.code, "missing jm3_version/img_host")
                }
                EndpointProbe(
                    baseUrl = base,
                    status = EndpointStatus.Ok,
                    latencyMs = latency,
                    httpStatus = response.code,
                    setting = SettingPayload(version, imageHost),
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

    private fun degraded(
        base: String,
        latencyMs: Long,
        httpStatus: Int,
        reason: String,
    ): EndpointProbe =
        EndpointProbe(
            baseUrl = base,
            status = EndpointStatus.Degraded,
            latencyMs = latencyMs,
            httpStatus = httpStatus,
            reason = reason,
        )

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

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
        const val IMAGE_PROBE_TIMEOUT_MS = 5_000L
        const val REMOTE_CONFIG_TIMEOUT_MS = 5_000L
        private const val MAX_PROBE_BYTES = 1024L * 1024L
        private const val PERF_TAG = "ReaderPerf"
    }
}
