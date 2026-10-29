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
 * Exercises the complete failover loop on Android's org.json: cached dead base -> hardcoded
 * fallbacks fail -> remote host config is fetched -> new base is probed -> request is retried.
 */
@RunWith(AndroidJUnit4::class)
class EndpointPoolE2ETest {
    private val apiSecret = "185Hcomic3PAPP7R"
    private val hostSecret = "diosfjckwpqpdfjkvnqQjsik"

    @Test
    fun deadCachedPoolRefreshesRemoteAndRetries() = runBlocking {
        val settingStore =
            FakeSettingStore(
                SettingSnapshot(
                    baseUrl = "https://dead.net",
                    version = "2.1.12",
                    imageHost = "https://img-dead.net",
                ),
            )
        val poolStore =
            FakePoolStore(
                EndpointPool(
                    orderedBases = listOf("https://dead.net"),
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
                            val encrypted =
                                encryptHostConfig("""{"Server":["new.net"]}""")
                            response(request, 200, encrypted)
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
                        url.host == "new.net" -> {
                            val ts = url.queryParameter("t")?.toLongOrNull() ?: 0L
                            response(
                                request,
                                200,
                                """{"code":200,"data":"${encryptPayload("""{"ok":true}""", ts)}"}""",
                            )
                        }
                        path.startsWith("/setting") -> response(request, 503, "{}")
                        else -> response(request, 500, "{}")
                    }
                }
                .build()

        val client = ApiClient(httpClient, settingStore, poolStore)
        val payload = client.getJson("/api")

        assertNotNull(payload.value)
        assertTrue((payload.value as JSONObject).optBoolean("ok"))
        assertEquals("https://new.net", settingStore.value?.baseUrl)
        assertEquals("https://img-new.net", settingStore.value?.imageHost)
        assertEquals("https://new.net", poolStore.value?.orderedBases?.first())
        assertTrue(poolStore.value?.orderedBases?.contains("https://dead.net") != true)
    }

    @Test
    fun staleCachedPoolRefreshesInBackground() = runBlocking {
        val oldSnapshot =
            SettingSnapshot(
                baseUrl = "https://old.net",
                version = "2.1.12",
                imageHost = "https://img-old.net",
            )
        val settingStore = FakeSettingStore(oldSnapshot)
        val poolStore =
            FakePoolStore(
                EndpointPool(
                    orderedBases = listOf("https://old.net"),
                    updatedAt = System.currentTimeMillis() - 31 * 60 * 1000L,
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
                            response(
                                request,
                                200,
                                encryptHostConfig("""{"Server":["new.net"]}"""),
                            )
                        }
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
                        else -> response(request, 503, "{}")
                    }
                }
                .build()

        val client = ApiClient(httpClient, settingStore, poolStore)
        // Cached snapshot must be returned without waiting for the stale pool refresh.
        assertEquals(oldSnapshot, client.ensureSetting())

        withTimeout(5_000L) {
            while (settingStore.value?.baseUrl != "https://new.net") {
                delay(50)
            }
        }
        assertEquals("https://new.net", poolStore.value?.orderedBases?.first())
        assertEquals("https://img-new.net", settingStore.value?.imageHost)
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

    private class FakeSettingStore(var value: SettingSnapshot?) : SettingSnapshotStore {
        override fun read(): SettingSnapshot? = value

        override fun write(snapshot: SettingSnapshot) {
            value = snapshot
        }

        override fun updateImageHost(imageHost: String) {
            value = value?.copy(imageHost = imageHost)
        }

        override fun clear() {
            value = null
        }
    }

    private class FakePoolStore(var value: EndpointPool?) : EndpointPoolStore {
        override fun read(): EndpointPool? = value

        override fun write(pool: EndpointPool) {
            value = pool
        }
    }
}
