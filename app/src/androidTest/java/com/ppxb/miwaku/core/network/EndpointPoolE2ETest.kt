package com.ppxb.miwaku.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the single-snapshot endpoint model: one persisted value, a failed base dropped from the
 * pool, one retry on the next base, and an image pool that rotates without touching the API base.
 */
@RunWith(AndroidJUnit4::class)
class EndpointPoolE2ETest {
    private val apiSecret = "185Hcomic3PAPP7R"
    private val hostSecret = "diosfjckwpqpdfjkvnqQjsik"

    @Test
    fun deadBaseWithExhaustedPoolReResolvesAndRetries() = runBlocking {
        val store =
            FakeEndpointsStore(
                RuntimeEndpoints(
                    apiBaseUrl = "https://dead.net",
                    version = "2.1.12",
                    imagePool = listOf("https://img-dead.net"),
                    hostPool = listOf("https://dead.net"),
                    updatedAt = System.currentTimeMillis(),
                ),
            )

        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    val path = url.encodedPath
                    when {
                        url.host.contains("bytepluses") -> {
                            response(request, 200, encryptHostConfig("""{"Server":["new.net"]}"""))
                        }
                        url.host == "dead.net" -> throw IOException("dead base")
                        url.host == "new.net" && path.startsWith("/setting") -> {
                            val ts = url.queryParameter("t")?.toLongOrNull() ?: 0L
                            val inner =
                                """{"jm3_version":"2.1.12","img_host":"https://img-new.net"}"""
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload(inner, ts)}"}""",
                            )
                        }
                        url.host == "img-new.net" -> response(request, 200, "gif")
                        url.host == "new.net" -> {
                            val ts = url.queryParameter("t")?.toLongOrNull() ?: 0L
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"ok":true}""", ts)}"}""",
                            )
                        }
                        else -> response(request, 503, "{}")
                    }
                }
                .build()

        val client = ApiClient(httpClient, store)
        val payload = client.getJson("/api")

        assertNotNull(payload.value)
        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://new.net", store.value?.apiBaseUrl)
        assertEquals(listOf("https://img-new.net"), store.value?.imagePool)
        assertTrue(store.value?.hostPool?.contains("https://dead.net") != true)
    }

    @Test
    fun imageHostRotatesWithoutTouchingTheApiBase() = runBlocking {
        val store =
            FakeEndpointsStore(
                RuntimeEndpoints(
                    apiBaseUrl = "https://api.net",
                    version = "2.1.12",
                    imagePool = listOf("https://img-a.net", "https://img-b.net"),
                    hostPool = listOf("https://api.net"),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        val client = ApiClient(OkHttpClient(), store)

        // A stored snapshot must be used as-is: no probe runs when it is still fresh.
        assertEquals("https://api.net", client.endpoints().apiBaseUrl)
        assertEquals("https://img-b.net", client.rotateImageHost())
        assertEquals("https://api.net", store.value?.apiBaseUrl)
        assertEquals(listOf("https://img-b.net"), store.value?.imagePool)
        // A single remaining host has nowhere to fail over to.
        assertNull(client.rotateImageHost())
    }

    @Test
    fun sharedStoreRoundTripsAndExpires() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SharedEndpointsStore(context)
        store.clear()

        val fresh =
            RuntimeEndpoints(
                apiBaseUrl = "https://api.net",
                version = "2.1.12",
                imagePool = listOf("https://img-a.net", "https://img-b.net"),
                hostPool = listOf("https://api.net", "https://api2.net"),
                updatedAt = System.currentTimeMillis(),
            )
        store.write(fresh)
        assertEquals(fresh, store.read())

        store.write(fresh.copy(updatedAt = System.currentTimeMillis() - EndpointsStore.TTL_MS - 1))
        assertNull("an expired snapshot must not be served", store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun probePrefersFastOkEndpointsAndBuildsTheImagePool() = runBlocking {
        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    val path = url.encodedPath
                    val ts = url.queryParameter("t")?.toLongOrNull() ?: 0L
                    when {
                        url.host.contains("bytepluses") -> {
                            response(
                                request,
                                200,
                                encryptHostConfig(
                                    """{"Server":["dead.net","degraded.net","slow.net","fast.net"]}""",
                                ),
                            )
                        }
                        url.host == "dead.net" -> throw IOException("unreachable")
                        url.host == "degraded.net" -> response(request, 502, "{}")
                        url.host == "slow.net" && path.startsWith("/setting") -> {
                            Thread.sleep(80)
                            val inner =
                                """{"jm3_version":"2.1.12","img_host":"https://img-fast.net"}"""
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload(inner, ts)}"}""",
                            )
                        }
                        url.host == "fast.net" && path.startsWith("/setting") -> {
                            // The winning API base advertises the slow image host on purpose, so the
                            // background probe has something to reorder.
                            val inner =
                                """{"jm3_version":"2.1.12","img_host":"https://img-slow.net"}"""
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload(inner, ts)}"}""",
                            )
                        }
                        url.host == "img-slow.net" -> {
                            Thread.sleep(80)
                            response(request, 200, "gif")
                        }
                        url.host == "img-fast.net" -> response(request, 200, "gif")
                        else -> response(request, 503, "{}")
                    }
                }
                .build()

        val client = ApiClient(httpClient)
        val first = client.endpoints()

        assertEquals("https://fast.net", first.apiBaseUrl)
        assertEquals("2.1.12", first.version)
        // ok before degraded, each by latency; the dead base never enters the pool.
        assertEquals(
            listOf("https://fast.net", "https://slow.net", "https://degraded.net"),
            first.hostPool,
        )
        // The first snapshot must not wait for the image probe: candidates keep /setting order.
        assertEquals(listOf("https://img-slow.net", "https://img-fast.net"), first.imagePool)

        // The image pool is probed in the background and reordered by its own latency.
        val ordered =
            withTimeout(5_000L) {
                var pool = client.endpoints().imagePool
                while (pool != listOf("https://img-fast.net", "https://img-slow.net")) {
                    delay(50)
                    pool = client.endpoints().imagePool
                }
                pool
            }
        assertEquals(listOf("https://img-fast.net", "https://img-slow.net"), ordered)
    }

    @Test
    fun nonEnvelopeJsonFailsOverInsteadOfTerminating() = runBlocking {
        val store =
            FakeEndpointsStore(
                RuntimeEndpoints(
                    apiBaseUrl = "https://poisoned.net",
                    version = "2.1.12",
                    imagePool = listOf("https://img.net"),
                    hostPool = listOf("https://poisoned.net", "https://good.net"),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    val ts = url.queryParameter("t")?.toLongOrNull() ?: 0L
                    when (url.host) {
                        // A gateway page: valid JSON, but not an API envelope.
                        "poisoned.net" -> response(request, 200, """{"error":"blocked"}""")
                        "good.net" ->
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"ok":true}""", ts)}"}""",
                            )
                        else -> response(request, 503, "{}")
                    }
                }
                .build()

        val client = ApiClient(httpClient, store)
        val payload = client.getJson("/api")

        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://good.net", store.value?.apiBaseUrl)
        assertTrue(store.value?.hostPool?.contains("https://poisoned.net") != true)
    }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun encryptHostConfig(json: String): String = encrypt(json, md5Hex(hostSecret))

    private fun encryptPayload(json: String, ts: Long): String =
        encrypt(json, md5Hex("$ts$apiSecret"))

    private fun encrypt(json: String, keyText: String): String {
        val key = SecretKeySpec(keyText.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return android.util.Base64.encodeToString(
            cipher.doFinal(json.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private class FakeEndpointsStore(var value: RuntimeEndpoints?) : EndpointsStore {
        override fun read(): RuntimeEndpoints? = value

        override fun write(endpoints: RuntimeEndpoints) {
            value = endpoints
        }

        override fun clear() {
            value = null
        }
    }
}
