package com.ppxb.miwaku.core.network

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecTest {

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun encryptPayload(json: String, ts: Long, secret: String): String {
        val key = SecretKeySpec(md5Hex("$ts$secret").toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return java.util.Base64.getEncoder()
            .encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodeJson_acceptsPlaintextObject() {
        assertEquals("""{"ok":true}""", Codec.decodeJson("""{"ok":true}""", TS))
    }

    @Test
    fun decodeJson_acceptsPlaintextArray() {
        assertEquals("""[1,2]""", Codec.decodeJson("""[1,2]""", TS))
    }

    @Test
    fun decodeJson_decryptsCiphertext() {
        val encrypted = encryptPayload("""{"ok":true}""", TS, ApiConfig.DATA_SECRETS.first())
        assertEquals("""{"ok":true}""", Codec.decodeJson(encrypted, TS))
    }

    @Test
    fun decodeJson_stripsBomFromPlaintext() {
        assertEquals("""{"ok":true}""", Codec.decodeJson("\uFEFF{\"ok\":true}", TS))
    }

    @Test
    fun decodeJson_returnsNullForGarbage() {
        assertNull(Codec.decodeJson("not-base64-random-gibberish", TS))
        assertNull(Codec.decodeJson("", TS))
    }

    private companion object {
        const val TS = 1_700_000_000L
    }
}
