package com.ppxb.miwaku.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestHeadersTest {
    private val ts = 1_700_000_000L
    private val version = "2.1.12"
    private val agent = "Mozilla/5.0 (Linux; Android 13; abcdef123 Build/X; wv) test"

    @Test
    fun apiHeadersCarryTheSignatureAndTheAgent() {
        val headers = RequestHeaders.api(ts, version, agent)

        assertEquals(Codec.token(ts), headers["token"])
        assertEquals("$ts,$version", headers["tokenparam"])
        assertEquals(agent, headers["user-agent"])
        assertEquals("*/*", headers["accept"])
    }

    @Test
    fun imageHeadersCarryNoToken() {
        val headers = RequestHeaders.image(agent)

        assertNull(headers["token"])
        assertNull(headers["tokenparam"])
        assertEquals("image/*", headers["accept"])
        assertEquals(agent, headers["user-agent"])
    }

    @Test
    fun configHeadersOnlyIdentifyTheApp() {
        val headers = RequestHeaders.config(agent)

        assertEquals(agent, headers["user-agent"])
        assertNull(headers["accept"])
        assertNull(headers["token"])
    }
}
