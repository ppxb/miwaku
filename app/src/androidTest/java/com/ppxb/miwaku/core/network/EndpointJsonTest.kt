package com.ppxb.miwaku.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JSON-dependent host resolution runs on a device because the platform's org.json is stubbed in
 * JVM unit tests. Both cases go through the public [ApiClient] surface.
 */
@RunWith(AndroidJUnit4::class)
class EndpointJsonTest {
    private val apiSecret = "185Hcomic3PAPP7R"
    private val hostSecret = "diosfjckwpqpdfjkvnqQjsik"

    @Test
    fun objectItemsInTheHostConfigAreAccepted() = runBlocking {
        val store = FakeStateStore()
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("""{"Server":[{"host":"new.net"}]}"""))
                        url.encodedPath.endsWith("nopic-.gif") -> response(request, 200, "GIF89a")
                        url.host == "new.net" && url.encodedPath.startsWith("/setting") ->
                            settingResponse(request, "https://img-new.net")
                        url.host == "new.net" -> apiResponse(request)
                        url.encodedPath.startsWith("/setting") -> response(request, 503, "{}")
                        else -> response(request, 500, "{}")
                    }
                },
                store,
            )

        val payload = client.getJson("/api")

        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://new.net", store.value?.chosenApi)
    }

    @Test
    fun imageHostThatOnlyFailsThePlaceholderStaysInThePool() = runBlocking {
        val store = FakeStateStore()
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("""{"Server":["new.net"]}"""))
                        url.encodedPath.endsWith("nopic-.gif") -> response(request, 404, "")
                        url.host == "new.net" && url.encodedPath.startsWith("/setting") ->
                            settingResponse(request, "https://img-new.net")
                        url.host == "new.net" -> apiResponse(request)
                        url.encodedPath.startsWith("/setting") -> response(request, 503, "{}")
                        else -> response(request, 500, "{}")
                    }
                },
                store,
            )

        client.getJson("/api")

        assertEquals("https://img-new.net", store.value?.chosenImage)
        val image = store.value?.image.orEmpty()
        assertEquals(1, image.size)
        assertEquals(0, image.single().failures)
    }

    @Test
    fun hostConfigIsJsonParsedAndNormalized() = runBlocking {
        val store = FakeStateStore()
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("\uFEFF{\"Server\":[\"cdngwc.cc/\"]}"))
                        url.encodedPath.endsWith("nopic-.gif") -> response(request, 200, "GIF89a")
                        url.encodedPath.startsWith("/setting") ->
                            if (url.host == "cdngwc.cc") {
                                settingResponse(request, "https://img-new.net")
                            } else {
                                response(request, 503, "{}")
                            }
                        url.host == "cdngwc.cc" -> apiResponse(request)
                        else -> response(request, 500, "{}")
                    }
                },
                store,
            )

        client.getJson("/api")

        assertEquals("https://cdngwc.cc", store.value?.chosenApi)
    }

    @Test
    fun nonSuccessResponseKeepsTheServerMessage() = runBlocking {
        val store = FakeStateStore()
        val client =
            ApiClient(
                interceptorClient { request ->
                    val url = request.url
                    when {
                        url.host.contains("bytepluses") ->
                            response(request, 200, encryptHostConfig("""{"Server":["new.net"]}"""))
                        url.encodedPath.endsWith("nopic-.gif") -> response(request, 200, "GIF89a")
                        url.encodedPath.startsWith("/setting") -> settingResponse(request, "https://img-new.net")
                        else -> response(request, 520, """{"code":520,"msg":"請稍後再試"}""")
                    }
                },
                store,
            )

        val failure = runCatching { client.getJson("/album") }.exceptionOrNull()

        assertTrue("expected an HTTP failure, got $failure", failure is ApiFailure.Http)
        val http = failure as ApiFailure.Http
        assertEquals(520, http.code)
        assertEquals("請稍後再試", http.serverMessage)
    }

    private fun interceptorClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun settingResponse(request: Request, imageHost: String): Response {
        val ts = request.url.queryParameter("t")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000L)
        val inner = """{"jm3_version":"2.1.12","img_host":"$imageHost"}"""
        return response(request, 200, """{"code":200,"data":"${encryptPayload(inner, ts)}"}""")
    }

    private fun apiResponse(request: Request): Response {
        val ts = request.url.queryParameter("t")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000L)
        return response(request, 200, """{"code":200,"data":"${encryptPayload("""{"ok":true}""", ts)}"}""")
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

    private class FakeStateStore : EndpointStateStore {
        var value: EndpointState? = null

        override fun read(): EndpointState? = value

        override fun write(state: EndpointState) {
            value = state
        }
    }
}
