package com.ppxb.miwaku.core.data

import android.content.Context
import androidx.core.content.edit
import com.ppxb.miwaku.core.data.settings.ReadingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Per-album reading mode overrides. A missing album means "follow the default from
 * [com.ppxb.miwaku.core.data.settings.SettingsStore]".
 */
class ReaderModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_modes", Context.MODE_PRIVATE)
    private val _modes = MutableStateFlow(load())
    val modes: StateFlow<Map<String, ReadingMode>> = _modes.asStateFlow()

    @Synchronized
    fun setMode(albumId: String, mode: ReadingMode?) {
        if (albumId.isBlank()) return
        val next = if (mode == null) _modes.value - albumId else _modes.value + (albumId to mode)
        _modes.value = next
        prefs.edit { putString(KEY, encode(next)) }
    }

    private fun load(): Map<String, ReadingMode> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val albumId = keys.next()
                    val mode = ReadingMode.entries.firstOrNull { it.name == root.optString(albumId) }
                    if (albumId.isNotBlank() && mode != null) put(albumId, mode)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encode(modes: Map<String, ReadingMode>): String {
        val root = JSONObject()
        modes.forEach { (albumId, mode) -> root.put(albumId, mode.name) }
        return root.toString()
    }

    private companion object {
        const val KEY = "modes"
    }
}
