package com.ppxb.miwaku.core.network

import android.content.Context
import org.json.JSONArray

/**
 * Single source of truth for where the API and the images live.
 *
 * Modelled after Breeze-plugin-JmComic: the pool is a persisted value, not a concurrent state.
 * A request uses [apiBaseUrl] / [imageBaseUrl] directly; a failoverable failure drops that entry
 * from the pool and the next one is tried once. Nothing else tracks "the current base".
 */
data class RuntimeEndpoints(
    val apiBaseUrl: String,
    val version: String,
    /** Ordered by probe latency; the head is the current image host. */
    val imagePool: List<String>,
    /** Ordered by probe result (ok before degraded, each by latency); the head is the current API base. */
    val hostPool: List<String>,
    val updatedAt: Long,
) {
    val imageBaseUrl: String get() = imagePool.firstOrNull().orEmpty()
}

interface EndpointsStore {
    /** Returns the persisted snapshot, or null once it is older than [TTL_MS]. */
    fun read(): RuntimeEndpoints?

    fun write(endpoints: RuntimeEndpoints)

    fun clear()

    companion object {
        /** The API and image hosts move; a stale snapshot is worse than one more probe. */
        const val TTL_MS = 30 * 60 * 1000L
    }
}

class SharedEndpointsStore(context: Context) : EndpointsStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(): RuntimeEndpoints? {
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > EndpointsStore.TTL_MS) {
            return null
        }
        val apiBaseUrl = prefs.getString(KEY_API_BASE, null)?.takeIf { it.isNotBlank() } ?: return null
        val version = prefs.getString(KEY_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        val hostPool = readPool(KEY_HOST_POOL)
        if (hostPool.isEmpty()) return null
        val imagePool = readPool(KEY_IMAGE_POOL)
        return RuntimeEndpoints(
            apiBaseUrl = apiBaseUrl,
            version = version,
            imagePool = imagePool.ifEmpty { listOf(apiBaseUrl) },
            hostPool = hostPool,
            updatedAt = updatedAt,
        )
    }

    override fun write(endpoints: RuntimeEndpoints) {
        prefs.edit()
            .putString(KEY_API_BASE, endpoints.apiBaseUrl)
            .putString(KEY_VERSION, endpoints.version)
            .putString(KEY_IMAGE_POOL, encodePool(endpoints.imagePool))
            .putString(KEY_HOST_POOL, encodePool(endpoints.hostPool))
            .putLong(KEY_UPDATED_AT, endpoints.updatedAt)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readPool(key: String): List<String> {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val base = array.optString(index).trim().trimEnd('/')
                if (base.isNotEmpty()) add(base)
            }
        }.distinct()
    }

    private fun encodePool(pool: List<String>): String {
        val array = JSONArray()
        pool.distinct().forEach(array::put)
        return array.toString()
    }

    private companion object {
        const val PREFS_NAME = "runtime_endpoints"
        const val KEY_API_BASE = "api_base_url"
        const val KEY_VERSION = "version"
        const val KEY_IMAGE_POOL = "image_pool"
        const val KEY_HOST_POOL = "host_pool"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
