package com.ppxb.miwaku.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecHostListTest {

    @Test
    fun parsesStringServerArray() {
        val hosts = Codec.parseHostList("""{"Server":["www.a.net","b.cc/","https://c.net"]}""")

        assertEquals(listOf("https://www.a.net", "https://b.cc", "https://c.net"), hosts)
    }

    @Test
    fun fallsBackToRegexWhenAPreambleBreaksJson() {
        val hosts = Codec.parseHostList("bytedance cdn preamble\n{\"Server\":[\"x.net\"]}\ntrailer")

        assertEquals(listOf("https://x.net"), hosts)
    }

    @Test
    fun ignoresAnEmptyServerArray() {
        assertNull(Codec.parseHostList("""{"Server":[]}"""))
    }

    @Test
    fun ignoresTextWithoutAHostList() {
        assertNull(Codec.parseHostList("upstream unavailable"))
        assertNull(Codec.parseHostList(""))
    }
}
