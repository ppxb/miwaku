package com.ppxb.miwaku.core.network

/**
 * Runtime health of one endpoint. A failure only lowers the rank and starts a cooldown; the entry
 * stays in the pool, because one WAF page or one timeout must not permanently drop a working host.
 */
data class EndpointHealth(
    val url: String,
    val failures: Int = 0,
    val cooldownUntil: Long = 0L,
    val latencyMs: Long = 0L,
    val lastSuccessAt: Long = 0L,
) {
    fun isCoolingDown(now: Long): Boolean = cooldownUntil > now

    /**
     * A decode failure usually means a WAF/interstitial page rather than a slow link, so it cools
     * down harder than a timeout. Repeated failures back off exponentially up to [MAX_COOLDOWN_MS].
     */
    fun punished(now: Long, suspicious: Boolean): EndpointHealth {
        val nextFailures = failures + 1
        val base = if (suspicious) SUSPICIOUS_COOLDOWN_MS else TRANSIENT_COOLDOWN_MS
        val cooldown = (base shl (nextFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_COOLDOWN_MS)
        return copy(failures = nextFailures, cooldownUntil = now + cooldown)
    }

    fun recovered(now: Long, latency: Long = latencyMs): EndpointHealth =
        copy(failures = 0, cooldownUntil = 0L, latencyMs = latency, lastSuccessAt = now)

    private companion object {
        const val TRANSIENT_COOLDOWN_MS = 30_000L
        const val SUSPICIOUS_COOLDOWN_MS = 120_000L
        const val MAX_COOLDOWN_MS = 10 * 60_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}
