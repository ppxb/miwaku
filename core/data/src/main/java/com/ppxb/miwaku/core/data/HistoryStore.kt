package com.ppxb.miwaku.core.data

import android.content.Context
import androidx.core.content.edit
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ComicSummary>> = _entries.asStateFlow()

    @Synchronized
    fun record(comic: ComicSummary) {
        val next = (listOf(comic) + _entries.value.filterNot { it.id == comic.id }).take(MAX_ENTRIES)
        _entries.value = next
        prefs.edit { putString(KEY, encode(next)) }
    }

    private fun load(): List<ComicSummary> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    add(
                        ComicSummary(
                            id = id,
                            title = item.optString("title"),
                            author = item.optString("author"),
                            coverUrl = item.optString("coverUrl"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<ComicSummary>): String {
        val array = JSONArray()
        entries.forEach { comic ->
            array.put(
                JSONObject()
                    .put("id", comic.id)
                    .put("title", comic.title)
                    .put("author", comic.author)
                    .put("coverUrl", comic.coverUrl),
            )
        }
        return array.toString()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 50
    }
}
