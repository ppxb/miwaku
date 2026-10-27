package com.ppxb.miwaku.core.data

import android.content.Context
import androidx.core.content.edit
import com.ppxb.miwaku.model.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, ReadingProgress>> = _entries.asStateFlow()

    @Synchronized
    fun record(albumId: String, chapterId: String, page: Int) {
        if (albumId.isBlank() || chapterId.isBlank()) return
        val next = _entries.value + (albumId to ReadingProgress(chapterId, page.coerceAtLeast(0)))
        _entries.value = next
        prefs.edit { putString(KEY, encode(next)) }
    }

    private fun load(): Map<String, ReadingProgress> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val albumId = keys.next()
                    val item = root.optJSONObject(albumId) ?: continue
                    val chapterId = item.optString("chapterId")
                    if (albumId.isBlank() || chapterId.isBlank()) continue
                    put(albumId, ReadingProgress(chapterId, item.optInt("page", 0).coerceAtLeast(0)))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encode(entries: Map<String, ReadingProgress>): String {
        val root = JSONObject()
        entries.forEach { (albumId, progress) ->
            root.put(
                albumId,
                JSONObject()
                    .put("chapterId", progress.chapterId)
                    .put("page", progress.page),
            )
        }
        return root.toString()
    }

    private companion object {
        const val KEY = "entries"
    }
}
