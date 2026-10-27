package com.ppxb.miwaku.core.data

import android.content.Context
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short-lived album detail cache. It only exists to avoid paying /album again when the
 * user backs out and reopens a work; the TTL keeps newly added chapters from being hidden
 * for long.
 */
class ComicDetailCache(context: Context) {
    private val dir = File(context.cacheDir, "$CACHE_DIR/v$CACHE_VERSION")

    @Synchronized
    fun get(id: String): ComicDetail? {
        val file = fileFor(id)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) return null
        return runCatching { decode(file) }.getOrNull()
    }

    @Synchronized
    fun put(detail: ComicDetail) {
        if (detail.id.isBlank()) return
        runCatching {
            dir.mkdirs()
            val target = fileFor(detail.id)
            val temp = File.createTempFile("${target.name}.", ".tmp", dir)
            try {
                temp.writeText(encode(detail))
                target.delete()
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("cannot write detail cache")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            prune()
        }
    }

    @Synchronized
    fun sizeBytes(): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    @Synchronized
    fun clear() {
        runCatching {
            dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        }
    }

    private fun encode(detail: ComicDetail): String =
        JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("author", detail.author)
            .put("description", detail.description)
            .put("coverUrl", detail.coverUrl)
            .put(
                "tags",
                JSONArray().apply {
                    detail.tags.forEach { put(it) }
                },
            ).put(
                "chapters",
                JSONArray().apply {
                    detail.chapters.forEach { chapter ->
                        put(
                            JSONObject()
                                .put("id", chapter.id)
                                .put("name", chapter.name)
                                .put("sort", chapter.sort),
                        )
                    }
                },
            ).toString()

    private fun decode(file: File): ComicDetail? {
        val json = JSONObject(file.readText())
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val tagsJson = json.optJSONArray("tags") ?: JSONArray()
        val tags =
            buildList {
                for (index in 0 until tagsJson.length()) {
                    tagsJson.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        val chaptersJson = json.optJSONArray("chapters") ?: JSONArray()
        val chapters =
            buildList {
                for (index in 0 until chaptersJson.length()) {
                    val item = chaptersJson.optJSONObject(index) ?: continue
                    val chapterId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        ChapterSummary(
                            id = chapterId,
                            name = item.optString("name"),
                            sort = item.optInt("sort"),
                        ),
                    )
                }
            }
        return ComicDetail(
            id = id,
            title = json.optString("title"),
            author = json.optString("author"),
            description = json.optString("description"),
            tags = tags,
            coverUrl = json.optString("coverUrl"),
            chapters = chapters,
        )
    }

    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files
            .sortedBy { it.lastModified() }
            .dropLast(MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun fileFor(id: String): File {
        val name =
            id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .ifEmpty { "detail" }
        return File(dir, "$name.json")
    }

    private companion object {
        const val CACHE_DIR = "reader_details"
        const val CACHE_VERSION = 1
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 16
    }
}
