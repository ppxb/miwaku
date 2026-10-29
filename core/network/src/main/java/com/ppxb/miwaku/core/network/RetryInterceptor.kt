package com.ppxb.miwaku.core.network

import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retries idempotent requests a limited number of times. Only transport failures and transient
 * status codes are retried; other 4xx responses are returned untouched so [ApiFailure] can
 * classify them.
 */
class RetryInterceptor(
    private val maxRetries: Int = 1,
    private val baseDelayMs: Long = 250L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") return chain.proceed(request)

        var attempt = 0
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                val response = chain.proceed(request)
                if (attempt >= maxRetries || !response.isTransient()) return response
                val delayMs = backoffMs(attempt, response.code)
                response.close()
                NetworkMetrics.recordRetry()
                sleep(delayMs)
                attempt++
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                if (attempt >= maxRetries) throw e
                NetworkMetrics.recordRetry()
                sleep(backoffMs(attempt, null))
                attempt++
            }
        }
    }

    private fun Response.isTransient(): Boolean =
        code == 408 ||
            code == 429 ||
            code == 500 ||
            code == 502 ||
            code == 503 ||
            code == 504 ||
            code == 520 ||
            code == 524

    private fun backoffMs(attempt: Int, code: Int?): Long {
        if (code == 429) return 1_000L
        val base = if (attempt == 0) baseDelayMs else baseDelayMs * 3
        val jitter = ThreadLocalRandom.current().nextLong(0L, 251L)
        return base + jitter
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted", e)
        }
    }
}
