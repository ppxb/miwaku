package com.ppxb.miwaku.core.data.image

import android.content.Context
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ReaderPage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class CachedPage(
    val fileName: String,
    val splitCount: Int,
)

data class CachedChapter(
    val chapterId: String,
    val seriesId: String,
    val title: String,
    val imageHost: String,
    val pages: List<CachedPage>,
) {
    fun toContent(host: String): ChapterContent {
        val base = host.trimEnd('/')
        return ChapterContent(
            albumId = seriesId.ifBlank { chapterId },
            chapterId = chapterId,
            title = title,
            pages =
                pages.map { page ->
                    ReaderPage(
                        url = "$base/media/photos/$chapterId/${page.fileName}",
                        splitCount = page.splitCount,
                        fileName = page.fileName,
                    )
                },
        )
    }
}

class ChapterManifestCache(context: Context) {
    private val dir = File(context.cacheDir, "$CACHE_DIR/v$CACHE_VERSION")

    fun sizeBytes(): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    fun clear() {
        runCatching {
            dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        }
    }

    fun get(chapterId: String): CachedChapter? {
        val file = fileFor(chapterId)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val pagesJson = json.optJSONArray("pages") ?: JSONArray()
            val pages =
                buildList {
                    for (index in 0 until pagesJson.length()) {
                        val item = pagesJson.optJSONObject(index) ?: continue
                        val name = item.optString("name")
                        if (name.isBlank()) continue
                        add(CachedPage(name, item.optInt("split", 0)))
                    }
                }
            if (pages.isEmpty()) return@runCatching null
            CachedChapter(
                chapterId = chapterId,
                seriesId = json.optString("seriesId"),
                title = json.optString("title"),
                imageHost = json.optString("imageHost"),
                pages = pages,
            )
        }.getOrNull()
    }

    fun put(
        chapterId: String,
        seriesId: String,
        title: String,
        imageHost: String,
        pages: List<ReaderPage>,
    ) {
        if (pages.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val json =
                JSONObject()
                    .put("savedAt", System.currentTimeMillis())
                    .put("seriesId", seriesId)
                    .put("title", title)
                    .put("imageHost", imageHost.trimEnd('/'))
                    .put(
                        "pages",
                        JSONArray().apply {
                            pages.forEach { page ->
                                put(
                                    JSONObject()
                                        .put("name", page.fileName)
                                        .put("split", page.splitCount),
                                )
                            }
                        },
                    )
            val target = fileFor(chapterId)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(json.toString())
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
            }
            prune()
        }
    }

    private fun prune() {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return
        if (files.size <= MAX_ENTRIES) return
        files
            .sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun fileFor(chapterId: String): File {
        val safe =
            chapterId
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .ifEmpty { "chapter" }
        return File(dir, "$safe.json")
    }

    private companion object {
        const val CACHE_DIR = "reader_manifests"
        const val CACHE_VERSION = 1
        const val MAX_ENTRIES = 200
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
