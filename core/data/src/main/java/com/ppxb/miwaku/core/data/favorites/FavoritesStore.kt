package com.ppxb.miwaku.core.data.favorites

import android.content.Context
import androidx.core.content.edit
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteComic(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val savedAt: Long,
)

/** Local favorites; the JM API favorite endpoints need a login, this store does not. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<FavoriteComic>> = _entries.asStateFlow()

    fun isFavorite(id: String): Boolean = _entries.value.any { it.id == id }

    fun toggle(comic: ComicSummary) {
        val current = _entries.value
        val next =
            if (current.any { it.id == comic.id }) {
                current.filterNot { it.id == comic.id }
            } else {
                listOf(
                    FavoriteComic(
                        id = comic.id,
                        title = comic.title,
                        author = comic.author,
                        coverUrl = comic.coverUrl,
                        savedAt = System.currentTimeMillis(),
                    ),
                ) + current
            }
        _entries.value = next
        save(next)
    }

    private fun load(): List<FavoriteComic> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        FavoriteComic(
                            id = id,
                            title = item.optString("title"),
                            author = item.optString("author"),
                            coverUrl = item.optString("coverUrl"),
                            savedAt = item.optLong("savedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(entries: List<FavoriteComic>) {
        val array =
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("title", entry.title)
                            .put("author", entry.author)
                            .put("coverUrl", entry.coverUrl)
                            .put("savedAt", entry.savedAt),
                    )
                }
            }
        prefs.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "favorites"
        const val KEY_ENTRIES = "entries"
    }
}
