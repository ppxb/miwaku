package com.ppxb.miwaku.core.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface EndpointStateStore {
    fun read(): EndpointState?

    fun write(state: EndpointState)
}

/**
 * One versioned document in SharedPreferences: API pool, image pool, both selections and the
 * probe timestamp. The legacy `endpoint_pool` / `api_setting` documents are migrated on first read
 * and left untouched, so a downgrade still finds its data.
 *
 * Writes are cheap by construction: [EndpointState] only changes on probe results, failover and
 * penalty events, never per successful request.
 */
class SharedEndpointStateStore(context: Context) : EndpointStateStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val context = context.applicationContext

    override fun read(): EndpointState? {
        prefs.getString(KEY_STATE, null)?.let { raw ->
            return runCatching { parse(JSONObject(raw)) }.getOrNull()
        }
        return migrateLegacy()
    }

    override fun write(state: EndpointState) {
        prefs.edit().putString(KEY_STATE, encode(state).toString()).apply()
    }

    private fun migrateLegacy(): EndpointState? {
        val pool = runCatching {
            context.getSharedPreferences(LEGACY_POOL, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val setting = runCatching {
            context.getSharedPreferences(LEGACY_SETTING, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null

        val bases = runCatching {
            val raw = pool.getString(LEGACY_POOL_BASES, null) ?: return@runCatching emptyList()
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it) }
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        val chosenApi = setting.getString(LEGACY_SETTING_BASE, null)?.trim()?.trimEnd('/')
        val version = setting.getString(LEGACY_SETTING_VERSION, null).orEmpty()
        val imageHost = setting.getString(LEGACY_SETTING_IMAGE, null)?.trim()?.trimEnd('/')
        if (bases.isEmpty() && chosenApi.isNullOrEmpty()) return null

        val migrated =
            EndpointState(
                probedAt = pool.getLong(LEGACY_POOL_UPDATED_AT, 0L),
                apiVersion = version,
                api = (bases + listOfNotNull(chosenApi)).distinct().map { EndpointHealth(it) },
                image = listOfNotNull(imageHost).map { EndpointHealth(it) },
                chosenApi = chosenApi,
                chosenImage = imageHost,
            )
        write(migrated)
        return migrated
    }

    private fun encode(state: EndpointState): JSONObject =
        JSONObject()
            .put(KEY_VERSION, state.version)
            .put(KEY_PROBED_AT, state.probedAt)
            .put(KEY_API_VERSION, state.apiVersion)
            .put(KEY_API, encodePool(state.api))
            .put(KEY_IMAGE, encodePool(state.image))
            .put(KEY_CHOSEN_API, state.chosenApi.orEmpty())
            .put(KEY_CHOSEN_IMAGE, state.chosenImage.orEmpty())

    private fun encodePool(pool: List<EndpointHealth>): JSONArray =
        JSONArray().apply {
            pool.forEach { entry ->
                put(
                    JSONObject()
                        .put(KEY_URL, entry.url)
                        .put(KEY_FAILURES, entry.failures)
                        .put(KEY_COOLDOWN_UNTIL, entry.cooldownUntil)
                        .put(KEY_LATENCY, entry.latencyMs)
                        .put(KEY_LAST_SUCCESS_AT, entry.lastSuccessAt),
                )
            }
        }

    private fun parse(json: JSONObject): EndpointState =
        EndpointState(
            version = EndpointState.SCHEMA_VERSION,
            probedAt = json.optLong(KEY_PROBED_AT, 0L),
            apiVersion = json.optString(KEY_API_VERSION).orEmpty(),
            api = parsePool(json.optJSONArray(KEY_API)),
            image = parsePool(json.optJSONArray(KEY_IMAGE)),
            chosenApi = json.optString(KEY_CHOSEN_API).takeIf { it.isNotBlank() },
            chosenImage = json.optString(KEY_CHOSEN_IMAGE).takeIf { it.isNotBlank() },
        )

    private fun parsePool(array: JSONArray?): List<EndpointHealth> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString(KEY_URL).trim().trimEnd('/')
                if (url.isEmpty()) continue
                add(
                    EndpointHealth(
                        url = url,
                        failures = item.optInt(KEY_FAILURES, 0),
                        cooldownUntil = item.optLong(KEY_COOLDOWN_UNTIL, 0L),
                        latencyMs = item.optLong(KEY_LATENCY, 0L),
                        lastSuccessAt = item.optLong(KEY_LAST_SUCCESS_AT, 0L),
                    ),
                )
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "endpoint_state"
        const val KEY_STATE = "state"
        const val KEY_VERSION = "version"
        const val KEY_PROBED_AT = "probedAt"
        const val KEY_API_VERSION = "apiVersion"
        const val KEY_API = "api"
        const val KEY_IMAGE = "image"
        const val KEY_CHOSEN_API = "chosenApi"
        const val KEY_CHOSEN_IMAGE = "chosenImage"
        const val KEY_URL = "url"
        const val KEY_FAILURES = "failures"
        const val KEY_COOLDOWN_UNTIL = "cooldownUntil"
        const val KEY_LATENCY = "latencyMs"
        const val KEY_LAST_SUCCESS_AT = "lastSuccessAt"

        const val LEGACY_POOL = "endpoint_pool"
        const val LEGACY_POOL_BASES = "ordered_bases"
        const val LEGACY_POOL_UPDATED_AT = "updated_at"
        const val LEGACY_SETTING = "api_setting"
        const val LEGACY_SETTING_BASE = "base_url"
        const val LEGACY_SETTING_VERSION = "version"
        const val LEGACY_SETTING_IMAGE = "image_host"
    }
}
