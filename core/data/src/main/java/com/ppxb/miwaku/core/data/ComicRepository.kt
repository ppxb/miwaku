package com.ppxb.miwaku.core.data

import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.ApiPayload
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ReaderPage
import com.ppxb.miwaku.model.SearchResult
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class ComicRepository internal constructor(
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

    fun detail(id: String): ComicDetail {
        val payload = api.getJson("/album", mapOf("id" to id))
        val json = payload.value as? JSONObject ?: throw IOException("unexpected album payload")
        val title = json.string("name").unescape()
        val resolvedId =
            listOf(json.string("series_id"), json.string("id"), id)
                .firstOrNull { it.isNotBlank() && it != "0" && it != "null" }
                ?: id
        val chapters =
            json.optJSONArray("series").toChapterList().ifEmpty {
                listOf(ChapterSummary(id = resolvedId, name = title, sort = 1))
            }
        return ComicDetail(
            id = resolvedId,
            title = title,
            author = json.string("author").unescape(),
            description = json.string("description").plainText(),
            tags = json.optJSONArray("tags").toStringList(),
            coverUrl = "${payload.imageHost}/media/albums/${resolvedId}_3x4.jpg",
            chapters = chapters,
        )
    }

    fun chapter(albumId: String, chapterId: String): ChapterContent {
        val payload = api.getJson("/chapter", mapOf("id" to chapterId))
        val json = payload.value as? JSONObject ?: throw IOException("unexpected chapter payload")
        val images = json.optJSONArray("images").toStringList()
        val seriesId =
            json.string("series_id")
                .takeIf { it.isNotBlank() && it != "0" && it != "null" }
                ?: albumId
        val photoId = chapterId.toLongOrNull() ?: 0L
        val host = payload.imageHost.trimEnd('/')

        val pages =
            images.mapNotNull { filename ->
                if (filename.isBlank()) return@mapNotNull null
                val splitCount =
                    if (filename.endsWith(".gif", ignoreCase = true)) {
                        0
                    } else {
                        Scramble.splitCount(photoId, filename.substringBeforeLast('.'))
                    }
                ReaderPage(
                    url = "$host/media/photos/$chapterId/$filename",
                    splitCount = splitCount,
                )
            }
        return ChapterContent(
            albumId = seriesId,
            chapterId = chapterId,
            title = json.string("name").unescape(),
            pages = pages,
        )
    }

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

    private fun JSONArray?.toChapterList(): List<ChapterSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
                add(
                    ChapterSummary(
                        id = id,
                        name = item.string("name").unescape(),
                        sort = item.optInt("sort"),
                    ),
                )
            }
        }.sortedBy { it.sort }.distinctBy { it.id }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i)
                if (value.isNotBlank() && value != "null") add(value)
            }
        }
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun String.plainText(): String =
        replace(Regex("<[^>]+>"), " ")
            .unescape()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.unescape(): String =
        replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
