package com.ppxb.miwaku.core.network

import android.content.Context

interface SettingSnapshotStore {
    fun read(): SettingSnapshot?

    fun write(snapshot: SettingSnapshot)

    /** Replaces only the image host; the cached API base and its TTL stay untouched. */
    fun updateImageHost(imageHost: String)

    fun clear()
}

/**
 * Keeps a short-lived copy of /setting so a cold start does not have to wait for it.
 * The TTL is deliberately short because the API and image hosts can move.
 */
class SharedSettingSnapshotStore(context: Context) : SettingSnapshotStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(): SettingSnapshot? {
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > TTL_MS) return null
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val version = prefs.getString(KEY_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        val imageHost = prefs.getString(KEY_IMAGE_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        return SettingSnapshot(baseUrl, version, imageHost)
    }

    override fun write(snapshot: SettingSnapshot) {
        prefs.edit()
            .putString(KEY_BASE_URL, snapshot.baseUrl)
            .putString(KEY_VERSION, snapshot.version)
            .putString(KEY_IMAGE_HOST, snapshot.imageHost)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    override fun updateImageHost(imageHost: String) {
        prefs.edit().putString(KEY_IMAGE_HOST, imageHost).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "api_setting"
        const val KEY_BASE_URL = "base_url"
        const val KEY_VERSION = "version"
        const val KEY_IMAGE_HOST = "image_host"
        const val KEY_SAVED_AT = "saved_at"
        const val TTL_MS = 30 * 60 * 1000L
    }
}
