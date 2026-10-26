package com.ppxb.miwaku.data

import com.ppxb.miwaku.data.model.ComicSummary
import com.ppxb.miwaku.data.model.SearchResult
import com.ppxb.miwaku.data.network.ApiClient
import com.ppxb.miwaku.data.network.ApiPayload
import org.json.JSONArray
import org.json.JSONObject

internal class ComicRepository(
    private val api: ApiClient,
) {
    fun latest(page: Int = 0): List<ComicSummary> =
        api.getJson("/latest", mapOf("page" to "$page")).toComicList()

    fun search(query: String): SearchResult {
        val payload =
            api.getJson(
                "/search",
                mapOf("search_query" to query, "page" to "0"),
            )
        val json = payload.value as? JSONObject ?: return SearchResult(0, emptyList())
        val comics = (json.optJSONArray("content") ?: JSONArray()).toComicList(payload.imageHost)
        return SearchResult(json.optInt("total", comics.size), comics)
    }

    fun ranking(order: String, page: Int = 0): List<ComicSummary> =
        api.getJson(
            "/categories/filter",
            mapOf("page" to "$page", "order" to "", "c" to "", "o" to order),
        ).toComicList()

    private fun ApiPayload.toComicList(): List<ComicSummary> = value.toComicList(imageHost)

    private fun Any.toComicList(imageHost: String): List<ComicSummary> {
        val array =
            when (this) {
                is JSONArray -> this
                is JSONObject -> optJSONArray("content") ?: JSONArray()
                else -> JSONArray()
            }
        val base = imageHost.trimEnd('/')
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.isNull("id")) continue
                val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
                add(
                    ComicSummary(
                        id = id,
                        title = item.string("name").unescape(),
                        author = item.string("author").unescape(),
                        coverUrl = "$base/media/albums/${id}_3x4.jpg",
                    ),
                )
            }
        }.distinctBy { it.id }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun String.unescape(): String =
        replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
