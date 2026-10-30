package com.ppxb.miwaku.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHealthTest {
    private val now = 1_000_000L

    @Test
    fun transientFailureStartsACooldown() {
        val once = EndpointHealth("https://a").punished(now, suspicious = false)

        assertEquals(1, once.failures)
        assertTrue(once.cooldownUntil > now)
        assertTrue(once.isCoolingDown(now))
    }

    @Test
    fun repeatedFailuresBackOffExponentiallyAndStopAtTheCap() {
        val once = EndpointHealth("https://a").punished(now, suspicious = false)
        val twice = once.punished(now, suspicious = false)
        assertTrue(twice.cooldownUntil - now > once.cooldownUntil - now)

        var many = EndpointHealth("https://a")
        repeat(20) { many = many.punished(now, suspicious = false) }
        assertEquals(20, many.failures)
        assertTrue("cooldown must stay capped", many.cooldownUntil - now <= 10 * 60_000L)
    }

    @Test
    fun decodeFailureCoolsDownHarderThanATimeout() {
        val timeout = EndpointHealth("https://a").punished(now, suspicious = false)
        val waf = EndpointHealth("https://a").punished(now, suspicious = true)

        assertTrue(waf.cooldownUntil - now > timeout.cooldownUntil - now)
    }

    @Test
    fun recoveryClearsThePenalty() {
        val punished = EndpointHealth("https://a").punished(now, suspicious = true).punished(now, suspicious = true)
        val recovered = punished.recovered(now + 1)

        assertEquals(0, recovered.failures)
        assertEquals(0L, recovered.cooldownUntil)
        assertEquals(now + 1, recovered.lastSuccessAt)
        assertTrue(!recovered.isCoolingDown(now + 1))
    }
}
