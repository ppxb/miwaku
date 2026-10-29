package com.ppxb.miwaku.core.network

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainResolverTest {

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun encryptHostConfig(json: String, secret: String = ApiConfig.HOST_CONFIG_SECRET): String {
        val key = SecretKeySpec(md5Hex(secret).toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(encrypted)
    }

    private fun encryptPayload(json: String, ts: Long, secret: String = ApiConfig.DATA_SECRETS.first()): String {
        val key = SecretKeySpec(md5Hex("$ts$secret").toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(encrypted)
    }

    @Test
    fun testDecodeHostConfig_success() {
        val json = """{"Server": ["www.cdnhjk.net", "cdngwc.cc/", "https://cdngwc.net"]}"""
        val encrypted = encryptHostConfig(json)

        val domains = Codec.decodeHostConfig("\uFEFF$encrypted")
        assertNotNull("domains should not be null", domains)
        assertEquals(3, domains!!.size)
        assertEquals("https://www.cdnhjk.net", domains[0])
        assertEquals("https://cdngwc.cc", domains[1])
        assertEquals("https://cdngwc.net", domains[2])
    }

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
    fun testResolveCandidateDomains_remoteWins() = runBlocking {
        val json = """{"Server": ["dynamic-1.com", "dynamic-2.com"]}"""
        val encrypted = encryptHostConfig(json)

        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(encrypted.toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()

        val resolver = DomainResolver(client)
        val candidates = resolver.resolveCandidateDomains(
            sources = listOf("https://dummy.source/newsvr.txt"),
            fallbacks = listOf("https://fallback.net"),
        )
        assertEquals(listOf("https://dynamic-1.com", "https://dynamic-2.com"), candidates)
    }

    @Test
    fun testProbeSetting_sortsByLatencyAndFiltersDead() = runBlocking {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val url = chain.request().url.toString()
                    val ts = chain.request().url.queryParameter("t")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000L)
                    when {
                        url.startsWith("https://fast.net") -> {
                            val innerJson = """{"jm3_version": "2.1.12", "img_host": "https://img-fast.net"}"""
                            val outerJson = """{"code": 200, "data": "${encryptPayload(innerJson, ts)}"}"""
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(outerJson.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        url.startsWith("https://slow.net") -> {
                            Thread.sleep(80)
                            val innerJson = """{"jm3_version": "2.1.12", "img_host": "https://img-slow.net"}"""
                            val outerJson = """{"code": 200, "data": "${encryptPayload(innerJson, ts)}"}"""
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(outerJson.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        else -> {
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(502)
                                .message("Bad Gateway")
                                .body("{}".toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                    }
                }
                .build()

        val resolver = DomainResolver(client)
        val result = resolver.probeSetting(
            candidates = listOf("https://dead.net", "https://slow.net", "https://fast.net"),
            concurrency = 3,
        )

        assertEquals("https://fast.net", result.snapshot.baseUrl)
        assertEquals("https://img-fast.net", result.snapshot.imageHost)
        assertEquals("2.1.12", result.snapshot.version)
        assertEquals(listOf("https://fast.net", "https://slow.net"), result.orderedBases)
        assertTrue(result.latencyMs >= 0)
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
