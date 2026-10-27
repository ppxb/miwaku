package com.ppxb.miwaku.core.data

import com.ppxb.miwaku.core.data.image.ChapterManifestCache
import com.ppxb.miwaku.core.data.image.ReaderPerf
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.ApiPayload
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ExploreSection
import com.ppxb.miwaku.model.ReaderPage
import com.ppxb.miwaku.model.SearchResult
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class ComicRepository internal constructor(
    private val api: ApiClient,
    private val manifests: ChapterManifestCache,
) {
    private val detailCache = ConcurrentHashMap<String, CachedDetail>()
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

    fun exploreSections(): List<ExploreSection> {
        val payload = api.getJson("/promote")
        val array = payload.value as? JSONArray ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.string("title").unescapeHtml().trim()
                if (isUnsupportedSection(title)) continue
                val comics = item.optJSONArray("content")?.toComicList(payload.imageHost) ?: emptyList()
                if (comics.isEmpty()) continue
                add(
                    ExploreSection(
                        id = item.string("id"),
                        title = title,
                        comics = comics,
                    ),
                )
            }
        }
    }

    private fun isUnsupportedSection(title: String): Boolean =
        title in
            setOf(
                "禁漫小说",
                "禁漫书库",
                "禁漫書庫",
                "禁漫小說",
                "最新成人APP",
                "大人気エロ同人",
                "人気のエロ動画",
                "オススメ動画サイト",
            )

    fun detail(id: String): ComicDetail {
        val cached = detailCache[id]
        if (cached != null && System.currentTimeMillis() - cached.at < DETAIL_TTL_MS) {
            ReaderPerf.log { "album_cache_hit id=$id" }
            return cached.detail
        }

        val started = ReaderPerf.mark()
        val payload = api.getJson("/album", mapOf("id" to id))
        val json = payload.value as? JSONObject ?: throw IOException("unexpected album payload")
        val title = json.string("name").unescapeHtml()
        val resolvedId =
            listOf(json.string("series_id"), json.string("id"), id)
                .firstOrNull { it.isNotBlank() && it != "0" && it != "null" }
                ?: id
        val chapters =
            json.optJSONArray("series").toChapterList().ifEmpty {
                listOf(ChapterSummary(id = resolvedId, name = SINGLE_VOLUME, sort = 1))
            }
        val detail =
            ComicDetail(
                id = resolvedId,
                title = title,
                author = json.string("author").displayAuthor(),
                description = json.string("description").plainText(),
                tags = json.optJSONArray("tags").toStringList(),
                coverUrl = "${payload.imageHost}/media/albums/${resolvedId}_3x4.jpg",
                chapters = chapters,
            )
        if (detailCache.size >= MAX_DETAIL_CACHE) {
            detailCache.clear()
        }
        detailCache[id] = CachedDetail(System.currentTimeMillis(), detail)
        ReaderPerf.log { "album_loaded id=$id chapters=${chapters.size} ms=${ReaderPerf.mark() - started}" }
        return detail
    }

    fun chapter(albumId: String, chapterId: String): ChapterContent {
        val started = ReaderPerf.mark()
        manifests.get(chapterId)?.let { cached ->
            val host = api.imageHost() ?: cached.imageHost
            if (host.isNotBlank()) {
                val content = cached.toContent(host)
                ReaderPerf.log {
                    "manifest_hit chapter=$chapterId pages=${content.pages.size} ms=${ReaderPerf.mark() - started}"
                }
                return content
            }
        }

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
                    fileName = filename,
                )
            }
        val content =
            ChapterContent(
                albumId = seriesId,
                chapterId = chapterId,
                title = json.string("name").unescapeHtml(),
                pages = pages,
            )
        manifests.put(
            chapterId = chapterId,
            seriesId = seriesId,
            title = content.title,
            imageHost = payload.imageHost,
            pages = pages,
        )
        ReaderPerf.log {
            "manifest_loaded chapter=$chapterId pages=${pages.size} ms=${ReaderPerf.mark() - started}"
        }
        return content
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
                        title = item.string("name").unescapeHtml(),
                        author = item.string("author").displayAuthor(),
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
                val sort = item.optInt("sort")
                add(
                    ChapterSummary(
                        id = id,
                        name = item.string("name").chapterTitle(sort),
                        sort = sort,
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
            .unescapeHtml()
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class CachedDetail(
        val at: Long,
        val detail: ComicDetail,
    )

    private companion object {
        const val DETAIL_TTL_MS = 10 * 60 * 1000L
        const val MAX_DETAIL_CACHE = 16
    }
}
