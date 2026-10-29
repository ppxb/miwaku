package com.ppxb.miwaku.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.Response

/** Lightweight counters for the debug screen. Hosts and payloads are never recorded. */
object NetworkMetrics {
    private val requests = AtomicLong()
    private val failures = AtomicLong()
    private val retries = AtomicLong()
    private val throttled = AtomicLong()
    private val failovers = AtomicLong()
    private val totalMs = AtomicLong()

    data class Snapshot(
        val requests: Long,
        val failures: Long,
        val retries: Long,
        val throttled: Long,
        val failovers: Long,
        val averageMs: Long,
    )

    internal fun recordRequest(code: Int, elapsedMs: Long) {
        requests.incrementAndGet()
        totalMs.addAndGet(elapsedMs)
        if (code < 0 || code >= 400) failures.incrementAndGet()
        if (code == 429) throttled.incrementAndGet()
    }

    internal fun recordRetry() {
        retries.incrementAndGet()
    }

    internal fun recordFailover() {
        failovers.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        val count = requests.get()
        return Snapshot(
            requests = count,
            failures = failures.get(),
            retries = retries.get(),
            throttled = throttled.get(),
            failovers = failovers.get(),
            averageMs = if (count == 0L) 0 else totalMs.get() / count,
        )
    }
}

/** Records one sample per actual HTTP attempt, i.e. it sits inside [RetryInterceptor]. */
class NetworkMetricsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val started = elapsedMs()
        return try {
            val response = chain.proceed(chain.request())
            NetworkMetrics.recordRequest(response.code, elapsedMs() - started)
            response
        } catch (e: IOException) {
            NetworkMetrics.recordRequest(-1, elapsedMs() - started)
            throw e
        }
    }

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
