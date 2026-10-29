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

data class ProbeResult(
    val snapshot: SettingSnapshot,
    val orderedBases: List<String>,
    val latencyMs: Long,
)

private data class CandidateProbe(
    val base: String,
    val snapshot: SettingSnapshot?,
    val latencyMs: Long,
    val failureReason: String?,
)

/**
 * Domain resolver responsible for:
 * 1. Fetching remote dynamic domains from Bytepluses encrypted sources concurrently.
 * 2. Probing candidate base URLs against /setting concurrently (bounded to 6).
 * 3. Sorting healthy candidates by actual response latency and selecting the fastest.
 */
internal class DomainResolver(
    private val httpClient: OkHttpClient,
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
     * Filters for valid 200 responses with non-empty img_host and version, records latencies,
     * and returns the fastest endpoint's snapshot along with ordered fallback bases.
     */
    suspend fun probeSetting(
        candidates: List<String>,
        concurrency: Int = DEFAULT_PROBE_CONCURRENCY,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): ProbeResult {
        if (candidates.isEmpty()) {
            throw ApiFailure.Network("No candidate domains provided")
        }
        val semaphore = Semaphore(concurrency)
        val probes =
            coroutineScope {
                candidates.map { base ->
                    async {
                        semaphore.withPermit {
                            probeSingleSetting(base, timeoutMs)
                        }
                    }
                }.awaitAll()
            }

        val successful = probes.filter { it.snapshot != null }.sortedBy { it.latencyMs }
        if (successful.isEmpty()) {
            val summary = probes.joinToString("; ") { "${it.base.hostName()}: ${it.failureReason}" }
            throw ApiFailure.Network(summary.ifEmpty { "setting unavailable" })
        }

        val winner = successful.first()
        val orderedBases = successful.map { it.base }
        perf {
            "probed_winner host=${winner.base.hostName()} latency=${winner.latencyMs}ms available=${successful.size}/${candidates.size}"
        }
        return ProbeResult(
            snapshot = checkNotNull(winner.snapshot),
            orderedBases = orderedBases,
            latencyMs = winner.latencyMs,
        )
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

    private suspend fun probeSingleSetting(base: String, timeoutMs: Long): CandidateProbe {
        val ts = System.currentTimeMillis() / 1000L
        val start = System.nanoTime()
        return try {
            val url =
                "$base/setting?app_img_shunt=1&t=$ts".toHttpUrlOrNull()
                    ?: return CandidateProbe(base, null, Long.MAX_VALUE, "invalid url")
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
            val response = call.await()
            val latency = (System.nanoTime() - start) / 1_000_000L
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return CandidateProbe(base, null, latency, "HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: return CandidateProbe(base, null, latency, "empty body")
                val code = CODE_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                if (code != 200) {
                    val msg = ERROR_MSG_REGEX.find(body)?.groupValues?.get(1).orEmpty()
                    return CandidateProbe(base, null, latency, "code $code: $msg")
                }
                val data = DATA_REGEX.find(body)?.groupValues?.get(1)
                    ?: return CandidateProbe(base, null, latency, "missing data field")
                val decoded = Codec.decodeJson(data, ts)
                    ?: return CandidateProbe(base, null, latency, "decrypt failed")
                val version = VERSION_REGEX.find(decoded)?.groupValues?.get(1).orEmpty().trim()
                val imageHost = IMG_HOST_REGEX.find(decoded)?.groupValues?.get(1).orEmpty().replace("\\/", "/").trimEnd('/')
                if (version.isEmpty() || imageHost.isEmpty()) {
                    return CandidateProbe(base, null, latency, "missing jm3_version/img_host")
                }
                CandidateProbe(base, SettingSnapshot(base, version, imageHost), latency, null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val latency = (System.nanoTime() - start) / 1_000_000L
            CandidateProbe(base, null, latency, e.message ?: "network error")
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
        private const val PERF_TAG = "ReaderPerf"

        private val CODE_REGEX = Regex(""""code"\s*:\s*(\d+)""")
        private val DATA_REGEX = Regex(""""data"\s*:\s*"([^"]+)"""")
        private val VERSION_REGEX = Regex(""""jm3_version"\s*:\s*"([^"]+)"""")
        private val IMG_HOST_REGEX = Regex(""""img_host"\s*:\s*"([^"]+)"""")
        private val ERROR_MSG_REGEX = Regex(""""(?:errorMsg|message|msg)"\s*:\s*"([^"]+)"""")
    }
}
