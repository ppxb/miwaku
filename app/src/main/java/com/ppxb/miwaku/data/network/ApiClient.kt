package com.ppxb.miwaku.data.network

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal data class SettingSnapshot(
    val baseUrl: String,
    val version: String,
    val imageHost: String,
)

internal class ApiClient {
    fun fetchSetting(): Result<SettingSnapshot> {
        val failures = mutableListOf<String>()
        for (base in ApiConfig.API_BASES) {
            val ts = System.currentTimeMillis() / 1000
            val snapshot = runCatching { requestSetting(base, ts) }.getOrElse {
                failures += "${base.hostName()}: ${it.message}"
                null
            }
            if (snapshot != null) {
                return Result.success(snapshot)
            }
        }
        return Result.failure(IOException(failures.joinToString("; ")))
    }

    private fun requestSetting(base: String, ts: Long): SettingSnapshot? {
        val connection =
            (URL("$base/setting?app_img_shunt=1&t=$ts").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("token", Codec.token(ts))
                setRequestProperty("tokenparam", Codec.tokenParam(ts))
                setRequestProperty("user-agent", ApiConfig.USER_AGENT)
                setRequestProperty("accept", "*/*")
            }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status")
            }
            val body = connection.inputStream.use { it.readText(connection.contentEncoding) }
            val outer = JSONObject(body)
            if (outer.optInt("code") != 200) {
                throw IOException("code ${outer.optInt("code")}")
            }
            val decoded = Codec.decodeJson(outer.optString("data"), ts)
                ?: throw IOException("undecodable data")
            val setting = JSONObject(decoded)
            val version = setting.optString("jm3_version")
            val imageHost = setting.optString("img_host")
            if (version.isEmpty() || imageHost.isEmpty()) {
                throw IOException("missing jm3_version/img_host")
            }
            return SettingSnapshot(base, version, imageHost)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readText(encoding: String?): String {
        val stream = if (encoding?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(this) else this
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun String.hostName(): String = runCatching { URL(this).host }.getOrDefault(this)

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
