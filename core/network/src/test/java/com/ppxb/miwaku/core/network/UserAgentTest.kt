package com.ppxb.miwaku.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentTest {
    @Test
    fun theSameSeedAlwaysYieldsTheSameAgent() {
        val seed = "0123456789abcdef0123456789abcdef"

        assertEquals(UserAgent.fromSeed(seed), UserAgent.fromSeed(seed))
    }

    @Test
    fun differentSeedsYieldDifferentDevices() {
        assertNotEquals(UserAgent.fromSeed("seed-a"), UserAgent.fromSeed("seed-b"))
    }

    @Test
    fun aBlankSeedFallsBackToTheKnownConstant() {
        assertEquals(ApiConfig.USER_AGENT, UserAgent.fromSeed(""))
    }

    @Test
    fun theAgentHasTheMobileWebViewShape() {
        val agent = UserAgent.fromSeed("miwaku-test")

        assertTrue(agent.startsWith("Mozilla/5.0 (Linux; Android "))
        assertTrue(agent.endsWith("Mobile Safari/537.36"))
        assertTrue(
            "unexpected device block: $agent",
            Regex("Android (1[0-5]); [a-z0-9]{9} Build/").containsMatchIn(agent),
        )
    }

    @Test
    fun aFreshSeedLooksLikeAnOpaqueToken() {
        assertTrue(UserAgent.randomSeed().matches(Regex("[0-9a-f]{32}")))
    }
}
