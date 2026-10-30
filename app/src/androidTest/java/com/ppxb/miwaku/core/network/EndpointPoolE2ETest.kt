package com.ppxb.miwaku.core.network

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the failover loop on Android's org.json: a cached base that starts failing is
 * penalised instead of dropped, and a pool with no usable base is re-probed from the remote
 * host config before the request is retried.
 */
@RunWith(AndroidJUnit4::class)
class EndpointPoolE2ETest {
    private val apiSecret = "185Hcomic3PAPP7R"
    private val hostSecret = "diosfjckwpqpdfjkvnqQjsik"

    @Test
    fun penalisedBaseStaysInThePoolAndFallsBackToTheNextOne() = runBlocking {
        val store =
            FakeStateStore(
                EndpointState(
                    probedAt = System.currentTimeMillis(),
                    apiVersion = "2.1.12",
                    api = listOf(EndpointHealth("https://a.net"), EndpointHealth("https://b.net", latencyMs = 20)),
                    image = listOf(EndpointHealth("https://img-a.net")),
                    chosenApi = "https://a.net",
                    chosenImage = "https://img-a.net",
                ),
            )
        val client =
            ApiClient(
                interceptorClient { request ->
                    when (request.url.host) {
                        "a.net" -> throw IOException("flaky base")
                        "b.net" ->
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"ok":true}""", tsOf(request))}"}""",
                            )
                        else -> response(request, 500, "{}")
                    }
                },
                store,
            )

        val payload = client.getJson("/api")

        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://b.net", store.value?.chosenApi)
        val pool = store.value?.api.orEmpty()
        assertEquals("https://b.net", pool.first().url)
        val penalised = pool.first { it.url == "https://a.net" }
        assertTrue("penalised base must stay in the pool", penalised.failures >= 1)
        assertTrue("penalty must start a cooldown", penalised.cooldownUntil > System.currentTimeMillis())
    }

    @Test
    fun deadCachedPoolRefreshesRemoteAndRetries() = runBlocking {
        val store =
            FakeStateStore(
                EndpointState(
                    probedAt = System.currentTimeMillis(),
                    apiVersion = "2.1.12",
                    api = listOf(EndpointHealth("https://dead.net")),
                    image = listOf(EndpointHealth("https://img-dead.net")),
                    chosenApi = "https://dead.net",
                    chosenImage = "https://img-dead.net",
                ),
            )
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("""{"Server":["new.net"]}"""))
                        url.encodedPath.endsWith("nopic-.gif") ->
                            if (url.host == "img-new.net") response(request, 200, "GIF89a")
                            else response(request, 404, "")
                        url.host == "dead.net" -> throw IOException("dead base")
                        url.host == "new.net" && url.encodedPath.startsWith("/setting") ->
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"jm3_version":"2.1.12","img_host":"https://img-new.net"}""", tsOf(request))}"}""",
                            )
                        url.host == "new.net" ->
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"ok":true}""", tsOf(request))}"}""",
                            )
                        url.encodedPath.startsWith("/setting") -> response(request, 503, "{}")
                        else -> response(request, 500, "{}")
                    }
                },
                store,
            )

        val payload = client.getJson("/api")

        assertNotNull(payload.value)
        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://new.net", store.value?.chosenApi)
        assertEquals("https://img-new.net", store.value?.chosenImage)
        assertEquals("https://new.net", store.value?.api.orEmpty().first().url)
    }

    @Test
    fun staleCachedPoolRefreshesInBackground() = runBlocking {
        val store =
            FakeStateStore(
                EndpointState(
                    probedAt = System.currentTimeMillis() - 31 * 60 * 1000L,
                    apiVersion = "2.1.12",
                    api = listOf(EndpointHealth("https://old.net")),
                    image = listOf(EndpointHealth("https://img-old.net")),
                    chosenApi = "https://old.net",
                    chosenImage = "https://img-old.net",
                ),
            )
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("""{"Server":["new.net"]}"""))
                        url.encodedPath.endsWith("nopic-.gif") ->
                            if (url.host == "img-new.net") response(request, 200, "GIF89a")
                            else response(request, 404, "")
                        url.host == "new.net" && url.encodedPath.startsWith("/setting") ->
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"jm3_version":"2.1.12","img_host":"https://img-new.net"}""", tsOf(request))}"}""",
                            )
                        else -> response(request, 503, "{}")
                    }
                },
                store,
            )

        // The stale selection must still answer immediately.
        assertEquals("https://old.net", client.ensureSetting().baseUrl)

        withTimeout(5_000L) {
            while (store.value?.chosenApi != "https://new.net") {
                delay(50)
            }
        }
        assertEquals("https://img-new.net", store.value?.chosenImage)
    }

    private fun interceptorClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun tsOf(request: Request): Long =
        request.url.queryParameter("t")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000L)

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun encryptHostConfig(json: String): String = encrypt(json, md5Hex(hostSecret))

    private fun encryptPayload(json: String, ts: Long): String = encrypt(json, md5Hex("$ts$apiSecret"))

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

    private class FakeStateStore(var value: EndpointState?) : EndpointStateStore {
        override fun read(): EndpointState? = value

        override fun write(state: EndpointState) {
            value = state
        }
    }
}
