package com.ppxb.miwaku.core.network

import android.content.Context
import org.json.JSONArray

/**
 * Persisted health pool. Only non-dead bases are stored; their order is already
 * ok-by-latency followed by degraded-by-latency. [updatedAt] drives stale-while-revalidate.
 */
data class EndpointPool(
    val orderedBases: List<String>,
    val updatedAt: Long,
)

interface EndpointPoolStore {
    fun read(): EndpointPool?

    fun write(pool: EndpointPool)
}

class SharedEndpointPoolStore(context: Context) : EndpointPoolStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(): EndpointPool? {
        val raw = prefs.getString(KEY_BASES, null)?.takeIf { it.isNotBlank() } ?: return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val bases =
            buildList {
                for (index in 0 until array.length()) {
                    val base = array.optString(index).trim().trimEnd('/')
                    if (base.isNotEmpty()) add(base)
                }
            }
        if (bases.isEmpty()) return null
        return EndpointPool(
            orderedBases = bases.distinct(),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    override fun write(pool: EndpointPool) {
        val array = JSONArray()
        pool.orderedBases.distinct().forEach(array::put)
        prefs.edit()
            .putString(KEY_BASES, array.toString())
            .putLong(KEY_UPDATED_AT, pool.updatedAt)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "endpoint_pool"
        const val KEY_BASES = "ordered_bases"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
