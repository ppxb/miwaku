package com.ppxb.miwaku.core.network

import android.annotation.SuppressLint
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object Codec {
    fun token(ts: Long): String = md5Hex("$ts${ApiConfig.TOKEN_SECRET}")

    fun tokenParam(ts: Long, version: String = ApiConfig.APP_VERSION): String = "$ts,$version"

    /** Returns the decrypted JSON text, trying each known data secret. */
    fun decodeJson(data: String, ts: Long): String? {
        for (secret in ApiConfig.DATA_SECRETS) {
            val text = runCatching { decrypt(data, ts, secret) }.getOrNull()?.trim()?.removePrefix("\uFEFF")
            if (text != null && (text.startsWith("{") || text.startsWith("["))) {
                return text
            }
        }
        return null
    }

    @SuppressLint("GetInstance") // The remote protocol mandates AES-ECB for response decoding.
    private fun decrypt(data: String, ts: Long, secret: String): String {
        val normalized = normalizeBase64(data)
        val key = SecretKeySpec(md5Hex("$ts$secret").toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(Base64.decode(normalized, Base64.DEFAULT)), Charsets.UTF_8)
    }

    private fun normalizeBase64(raw: String): String {
        val compact = raw.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        val remainder = compact.length % 4
        return if (remainder == 0) compact else compact + "=".repeat(4 - remainder)
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
