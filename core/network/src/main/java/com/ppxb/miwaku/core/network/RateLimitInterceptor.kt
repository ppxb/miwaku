package com.ppxb.miwaku.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sliding-window limiter for the API client. Inspired by komikku's RateLimitInterceptor
 * (Apache-2.0), rewritten with a fair [ReentrantLock] so every request is counted once per
 * attempt, including retries.
 */
class RateLimitInterceptor(
    private val permits: Int,
    period: Duration = 1.seconds,
) : Interceptor {

    private val rateLimitMillis = period.inWholeMilliseconds
    private val lock = ReentrantLock(true)
    private val windowFull = lock.newCondition()
    private val timestamps = ArrayDeque<Long>(permits)

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        arm(call)
        return chain.proceed(chain.request())
    }

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

    private fun arm(call: okhttp3.Call) {
        lock.withLock {
            while (true) {
                val now = elapsedMs()
                val periodStart = now - rateLimitMillis
                var pruned = false
                while (timestamps.isNotEmpty() && timestamps.first() <= periodStart) {
                    timestamps.removeFirst()
                    pruned = true
                }
                if (pruned) windowFull.signalAll()
                if (timestamps.size < permits) {
                    timestamps.addLast(now)
                    return
                }
                if (call.isCanceled()) throw IOException("Canceled")
                val waitMs = (timestamps.first() - periodStart).coerceAtLeast(1L)
                try {
                    windowFull.await(waitMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while rate limiting", e)
                }
            }
        }
    }
}
