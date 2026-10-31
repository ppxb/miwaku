package com.ppxb.miwaku.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport-level behaviour only. Anything that has to parse the host config or the envelope needs
 * the platform's org.json, so those cases live in the device suite (EndpointJsonTest).
 */
class DomainResolverTest {

    @Test
    fun testResolveCandidateDomains_fallbackOnFailure() = runBlocking {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val resolver = DomainResolver(client)
        val candidates = resolver.resolveCandidateDomains(
            sources = listOf("https://dummy.source/newsvr.txt"),
            fallbacks = listOf("https://fallback.net"),
        )
        assertEquals(listOf("https://fallback.net"), candidates)
    }

    @Test
    fun testDecodeHostConfig_corruptedDataReturnsNull() {
        assertEquals(null, Codec.decodeHostConfig("not-base64-random-gibberish"))
        assertEquals(null, Codec.decodeHostConfig(""))
    }

    @Test(expected = ApiFailure.Network::class)
    fun testProbeSetting_allFailThrowsApiFailure() {
        runBlocking {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Down")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    .build()

            val resolver = DomainResolver(client)
            resolver.probeSetting(candidates = listOf("https://fail1.com", "https://fail2.com"))
        }
    }

    @Test
    fun testResolveCandidateDomains_fastFailureDoesNotWait() = runBlocking {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val resolver = DomainResolver(client)
        val started = System.currentTimeMillis()
        val candidates = resolver.resolveCandidateDomains(
            sources = listOf("https://s1.txt", "https://s2.txt"),
            fallbacks = listOf("https://fallback.net"),
            timeoutMs = 5_000L,
        )
        val elapsed = System.currentTimeMillis() - started
        assertEquals(listOf("https://fallback.net"), candidates)
        // Ensure all failed requests triggered immediate completion instead of blocking 5000ms
        assertTrue("Expected fast failure but took ${elapsed}ms", elapsed < 2_000L)
    }
}
