package com.ppxb.miwaku.data.network

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.json.JSONTokener

internal data class SettingSnapshot(
    val baseUrl: String,
    val version: String,
    val imageHost: String,
)

internal data class ApiPayload(
    val value: Any,
    val imageHost: String,
)

internal class ApiClient {
    @Volatile
    private var snapshot: SettingSnapshot? = null

    @Synchronized
    fun fetchSetting(): Result<SettingSnapshot> {
        snapshot?.let { return Result.success(it) }
        val failures = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS

        for (base in ApiConfig.API_BASES) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            val ts = now()
            val result =
                runCatching {
                    val setting =
                        requestJson(
                            base = base,
                            path = "/setting",
                            params = mapOf("app_img_shunt" to "1", "t" to "$ts"),
                            ts = ts,
                            version = ApiConfig.APP_VERSION,
                            timeoutMs = minOf(TIMEOUT_MS.toLong(), remaining).toInt(),
                        ) as? JSONObject ?: throw IOException("unexpected setting payload")
                    val version = setting.string("jm3_version")
                    val imageHost = setting.string("img_host").trimEnd('/')
                    if (version.isEmpty() || imageHost.isEmpty()) {
                        throw IOException("missing jm3_version/img_host")
                    }
                    SettingSnapshot(base, version, imageHost)
                }
            result.getOrNull()?.let {
                snapshot = it
                return Result.success(it)
            }
            failures += "${base.hostName()}: ${result.exceptionOrNull()?.message}"
        }
        return Result.failure(IOException(failures.joinToString("; ")))
    }

    fun getJson(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiPayload {
        val setting = fetchSetting().getOrThrow()
        val ts = now()
        return try {
            ApiPayload(
                value =
                    requestJson(
                        base = setting.baseUrl,
                        path = path,
                        params = params + ("t" to "$ts"),
                        ts = ts,
                        version = setting.version,
                        timeoutMs = TIMEOUT_MS,
                    ),
                imageHost = setting.imageHost,
            )
        } catch (e: Exception) {
            if (snapshot === setting) {
                snapshot = null
            }
            throw e
        }
    }

    private fun requestJson(
        base: String,
        path: String,
        params: Map<String, String>,
        ts: Long,
        version: String,
        timeoutMs: Int,
    ): Any {
        val query =
            params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        val url = URL("$base$path${if (query.isEmpty()) "" else "?$query"}")
        val connection =
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("token", Codec.token(ts))
                setRequestProperty("tokenparam", Codec.tokenParam(ts, version))
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
            val code = outer.optInt("code", -1)
            if (code != 200) {
                throw IOException("code $code")
            }
            val decoded = Codec.decodeJson(outer.optString("data"), ts)
                ?: throw IOException("undecodable data")
            return JSONTokener(decoded).nextValue()
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun InputStream.readText(encoding: String?): String {
        val stream = if (encoding?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(this) else this
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun String.hostName(): String = runCatching { URL(this).host }.getOrDefault(this)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun now(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val TOTAL_TIMEOUT_MS = 15_000L
    }
}
