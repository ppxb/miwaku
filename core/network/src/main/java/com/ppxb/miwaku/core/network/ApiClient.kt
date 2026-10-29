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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

data class ApiPayload(
    val value: Any,
    val imageHost: String,
)

/**
 * Single-source JM API client.
 *
 * Endpoint handling follows Breeze-plugin-JmComic: [RuntimeEndpoints] is the only place that knows
 * where the API and the images live. A request uses one base; a failoverable failure drops that base
 * from the pool and retries the next one exactly once. There is no per-request candidate list and no
 * background refresh state machine.
 */
class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val store: EndpointsStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = DomainResolver(httpClient)
    private val lock = Mutex()

    @Volatile
    private var cached: RuntimeEndpoints? = null

    @Volatile
    private var loadJob: Deferred<RuntimeEndpoints>? = null

    /** Returns the current snapshot, resolving it at most once for concurrent callers. */
    suspend fun endpoints(): RuntimeEndpoints {
        cached?.let { return it }
        val inFlight =
            lock.withLock {
                cached?.let { return@withLock null }
                val stored = withContext(Dispatchers.IO) { store?.read() }
                if (stored != null) {
                    cached = stored
                    return@withLock null
                }
                loadJob?.takeIf { it.isActive } ?: startResolve()
            }
        return inFlight?.await() ?: checkNotNull(cached)
    }

    fun imageHost(): String? = cached?.imageBaseUrl?.takeIf { it.isNotBlank() }

    /**
     * Rotates the image pool after a network-level image failure and returns the new head, or null
     * when there is nothing left to try. The API base is untouched: an image host outage must never
     * cost an API round trip.
     */
    suspend fun rotateImageHost(): String? =
        lock.withLock {
            val current = cached ?: return@withLock null
            if (current.imagePool.size <= 1) return@withLock null
            val remaining = current.imagePool.drop(1)
            val next = current.copy(imagePool = remaining)
            cached = next
            store?.write(next)
            perf { "image_host_rotated host=${remaining.first().hostName()}" }
            remaining.firstOrNull()
        }

    fun userAgent(): String = ApiConfig.USER_AGENT

    /**
     * Fetches a decoded API payload. A failoverable failure moves to the next base once; a business
     * error is thrown untouched because another base would answer the same way.
     */
    suspend fun getJson(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiPayload {
        val current = endpoints()
        return try {
            requestOnce(current, path, params)
        } catch (e: ApiFailure) {
            if (!e.canFailover) throw e
            val next = dropApiBase(current.apiBaseUrl) ?: reResolve(current) ?: throw e
            perf {
                "api_failover from=${current.apiBaseUrl.hostName()} " +
                    "to=${next.apiBaseUrl.hostName()} reason=${e.message}"
            }
            requestOnce(next, path, params)
        }
    }

    private suspend fun requestOnce(
        endpoints: RuntimeEndpoints,
        path: String,
        params: Map<String, String>,
    ): ApiPayload {
        val ts = now()
        val value =
            requestJson(
                base = endpoints.apiBaseUrl,
                path = path,
                params = params + ("t" to "$ts"),
                ts = ts,
                version = endpoints.version,
            )
        return ApiPayload(value, endpoints.imageBaseUrl)
    }

    private fun startResolve(): Deferred<RuntimeEndpoints> {
        val started = elapsedMs()
        val job =
            scope.async(start = CoroutineStart.LAZY) {
                val api = resolver.resolveApiBase()
                // The image candidates come straight from /setting and are usable as-is, so the
                // first request does not wait for the image probe. The pool is upgraded right after.
                val initial =
                    RuntimeEndpoints(
                        apiBaseUrl = api.apiBaseUrl,
                        version = api.version,
                        imagePool = api.imageCandidates,
                        hostPool = api.hostPool,
                        updatedAt = System.currentTimeMillis(),
                    )
                cached = initial
                store?.write(initial)
                perf {
                    "endpoints_resolved host=${api.apiBaseUrl.hostName()} " +
                        "apis=${api.hostPool.size} images=${api.imageCandidates.size} " +
                        "ms=${elapsedMs() - started}"
                }
                scope.launch { upgradeImagePool(initial) }
                initial
            }
        loadJob = job
        job.invokeOnCompletion { if (loadJob === job) loadJob = null }
        job.start()
        return job
    }

    /**
     * Replaces the unprobed image candidates with the ones that actually answered. Skipped when the
     * snapshot moved on in the meantime (rotated after a failure, or re-resolved).
     */
    private suspend fun upgradeImagePool(initial: RuntimeEndpoints) {
        val ordered = resolver.orderImagePool(initial.imagePool)
        if (ordered.isEmpty()) return
        lock.withLock {
            val current = cached ?: return@withLock
            if (current.apiBaseUrl != initial.apiBaseUrl) return@withLock
            if (current.imagePool != initial.imagePool) return@withLock
            val next = current.copy(imagePool = ordered)
            cached = next
            store?.write(next)
        }
    }

    /** Drops [failed] from the pool and promotes the next base. Returns null when nothing is left. */
    private suspend fun dropApiBase(failed: String): RuntimeEndpoints? =
        lock.withLock {
            val current = cached ?: return@withLock null
            if (current.hostPool.size <= 1) return@withLock null
            val remaining = current.hostPool.filterNot { it == failed }
            if (remaining.isEmpty() || remaining.size == current.hostPool.size) return@withLock null
            val next = current.copy(apiBaseUrl = remaining.first(), hostPool = remaining)
            cached = next
            store?.write(next)
            next
        }

    /**
     * The pool ran dry: probe the domain list from scratch once before giving up. If another caller
     * already refreshed while this request was in flight, its snapshot is reused.
     */
    private suspend fun reResolve(stale: RuntimeEndpoints): RuntimeEndpoints? =
        lock.withLock {
            val current = cached
            if (current != null && current.updatedAt > stale.updatedAt) return@withLock current
            cached = null
            val job = loadJob?.takeIf { it.isActive } ?: startResolve()
            try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun requestJson(
        base: String,
        path: String,
        params: Map<String, String>,
        ts: Long,
        version: String,
    ): Any {
        val body = requestText(base, path, params, ts, version)
        val outer =
            try {
                JSONObject(body)
            } catch (_: Exception) {
                throw ApiFailure.Decode("invalid envelope")
            }
        // A gateway/WAF JSON page has no `code` field. Treating it as a business error would make
        // the failure terminal and keep the request on a poisoned base; a decode failure fails over.
        if (!outer.has("code")) throw ApiFailure.Decode("not an envelope")
        val code = outer.optInt("code", -1)
        if (code != 200) {
            val message =
                sequenceOf("errorMsg", "msg", "message")
                    .map { outer.text(it) }
                    .firstOrNull { it.isNotBlank() }
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
        call.timeout().timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

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
        const val PERF_TAG = "ReaderPerf"
    }
}
