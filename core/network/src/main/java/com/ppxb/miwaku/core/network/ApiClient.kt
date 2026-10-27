package com.ppxb.miwaku.core.network

import android.util.Log
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

data class SettingSnapshot(
    val baseUrl: String,
    val version: String,
    val imageHost: String,
)

data class ApiPayload(
    val value: Any,
    val imageHost: String,
)

class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val settingStore: SettingSnapshotStore? = null,
) {
    @Volatile
    private var snapshot: SettingSnapshot? = null

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var cacheHitLogged = false

    private fun cachedSnapshot(): SettingSnapshot? {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    snapshot = runCatching { settingStore?.read() }.getOrNull()
                    cacheLoaded = true
                }
            }
        }
        return snapshot
    }

    fun fetchSetting(): Result<SettingSnapshot> {
        cachedSnapshot()?.let {
            if (!cacheHitLogged) {
                cacheHitLogged = true
                perf { "setting_cache_hit host=${it.baseUrl.hostName()}" }
            }
            return Result.success(it)
        }
        return synchronized(this) {
            cachedSnapshot()?.let { return@synchronized Result.success(it) }
            perf { "setting_fetch_start" }
            val started = System.currentTimeMillis()
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
                    cacheHitLogged = false
                    settingStore?.write(it)
                    perf {
                        "setting_fetched host=${it.baseUrl.hostName()} ms=${System.currentTimeMillis() - started}"
                    }
                    return Result.success(it)
                }
                failures += "${base.hostName()}: ${result.exceptionOrNull()?.message}"
            }
            perf {
                "setting_fetch_failed count=${failures.size} ms=${System.currentTimeMillis() - started}"
            }
            Result.failure(IOException(failures.joinToString("; ")))
        }
    }

    fun imageHost(): String? = cachedSnapshot()?.imageHost?.trimEnd('/')

    fun userAgent(): String = ApiConfig.USER_AGENT

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (snapshot === setting) {
                snapshot = null
                cacheHitLogged = false
                settingStore?.clear()
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
        val body =
            requestText(
                base = base,
                path = path,
                params = params,
                ts = ts,
                version = version,
                timeoutMs = timeoutMs,
            )
        val outer = JSONObject(body)
        val code = outer.optInt("code", -1)
        if (code != 200) {
            throw IOException("code $code")
        }
        val decoded = Codec.decodeJson(outer.optString("data"), ts)
            ?: throw IOException("undecodable data")
        return JSONTokener(decoded).nextValue()
    }

    private fun requestText(
        base: String,
        path: String,
        params: Map<String, String>,
        ts: Long,
        version: String,
        timeoutMs: Int,
    ): String {
        val baseUrl = "$base$path".toHttpUrlOrNull() ?: throw IOException("invalid url")
        val url =
            baseUrl
                .newBuilder()
                .apply {
                    params.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("token", Codec.token(ts))
                .header("tokenparam", Codec.tokenParam(ts, version))
                .header("user-agent", ApiConfig.USER_AGENT)
                .header("accept", "*/*")
                .build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("empty body")
        }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun String.hostName(): String = toHttpUrlOrNull()?.host ?: this

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun perf(message: () -> String) {
        if (Log.isLoggable(PERF_TAG, Log.DEBUG)) {
            Log.d(PERF_TAG, message())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val TOTAL_TIMEOUT_MS = 15_000L
        const val PERF_TAG = "ReaderPerf"
    }
}
