package com.ppxb.miwaku.core.network

import android.annotation.SuppressLint
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

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

    private val SERVER_ARRAY_REGEX =
        Regex(""""Server"\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val STRING_ITEM_REGEX = Regex(""""([^"]+)"""")

    /**
     * Decrypts the remote domain server list payload fetched from Bytepluses.
     * The payload is AES-ECB encrypted with the MD5 hex of [ApiConfig.HOST_CONFIG_SECRET].
     */
    @SuppressLint("GetInstance")
    fun decodeHostConfig(raw: String): List<String>? =
        runCatching {
            // Bytepluses files often carry a BOM, newlines, or a CDN preamble. Keep only
            // base64 alphabet characters before normalizing for the AES payload.
            val cleaned =
                raw.filter {
                    it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
                }
            if (cleaned.isEmpty()) return null
            val normalized = normalizeBase64(cleaned)
            val key = SecretKeySpec(md5Hex(ApiConfig.HOST_CONFIG_SECRET).toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decryptedBytes = cipher.doFinal(decodeBase64(normalized))
            val text = String(decryptedBytes, Charsets.UTF_8).trim().removePrefix("\uFEFF")
            parseHostList(text)
        }.getOrNull()

    /**
     * Parses the decrypted host document. JSON is authoritative; the regex path only exists for
     * payloads wrapped in a CDN preamble that makes the whole body invalid JSON.
     */
    internal fun parseHostList(text: String): List<String>? {
        val domains = jsonHosts(text) ?: regexHosts(text) ?: return null
        return domains.mapNotNull(::normalizeHost).distinct().ifEmpty { null }
    }

    private fun jsonHosts(text: String): List<String>? =
        runCatching {
            val array = JSONObject(text).optJSONArray("Server") ?: return@runCatching null
            buildList {
                for (index in 0 until array.length()) {
                    when (val item = array.opt(index)) {
                        is String -> add(item)
                        is JSONObject -> add(item.optString("host").ifBlank { item.optString("url") })
                        else -> Unit
                    }
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun regexHosts(text: String): List<String>? {
        val arrayContent = SERVER_ARRAY_REGEX.find(text)?.groupValues?.get(1) ?: return null
        return STRING_ITEM_REGEX.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    private fun normalizeHost(domain: String): String? {
        val trimmed = domain.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    @SuppressLint("GetInstance") // The remote protocol mandates AES-ECB for response decoding.
    private fun decrypt(data: String, ts: Long, secret: String): String {
        val normalized = normalizeBase64(data)
        val key = SecretKeySpec(md5Hex("$ts$secret").toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(decodeBase64(normalized)), Charsets.UTF_8)
    }

    private fun decodeBase64(str: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            Base64.decode(str, Base64.DEFAULT)
        }
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
