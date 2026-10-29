package com.ppxb.miwaku.core.data.download

import android.content.Context
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ReaderPage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class DownloadedPage(val fileName: String, val splitCount: Int)

data class DownloadedChapter(
    val albumId: String,
    val chapterId: String,
    val title: String,
    val imageHost: String,
    val pages: List<DownloadedPage>,
) {
    /** URLs are kept for the online fallback; the reader resolves the local file first. */
    fun toContent(host: String): ChapterContent {
        val base = host.trimEnd('/')
        return ChapterContent(
            albumId = albumId.ifBlank { chapterId },
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

/**
 * Owns offline downloads: one directory per chapter plus a small task list. Everything lives in
 * filesDir, never in the reader cache, so clearing the cache cannot delete downloaded chapters.
 */
class DownloadStore(context: Context) {
    private val root = File(context.filesDir, DOWNLOAD_DIR)
    private val tasksFile = File(root, TASKS_FILE)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    /** Scans finished chapters; called from an IO thread, never from the constructor. */
    fun refreshDownloaded() {
        _downloaded.value = scanDownloaded()
    }

    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun pageFile(chapterId: String, index: Int, fileName: String): File {
        val pagesDir = File(chapterDir(chapterId), PAGES_DIR)
        val safeName = sanitize(fileName).ifEmpty { "page" }
        return File(pagesDir, index.toString().padStart(4, '0') + "_" + safeName)
    }

    fun manifest(chapterId: String): DownloadedChapter? {
        val file = File(chapterDir(chapterId), MANIFEST_FILE)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val pagesJson = json.optJSONArray("pages") ?: JSONArray()
            val pages =
                buildList {
                    for (index in 0 until pagesJson.length()) {
                        val item = pagesJson.optJSONObject(index) ?: continue
                        val name = item.optString("name")
                        if (name.isBlank()) continue
                        add(DownloadedPage(name, item.optInt("split", 0)))
                    }
                }
            if (pages.isEmpty()) return@runCatching null
            DownloadedChapter(
                albumId = json.optString("albumId"),
                chapterId = chapterId,
                title = json.optString("title"),
                imageHost = json.optString("imageHost"),
                pages = pages,
            )
        }.getOrNull()
    }

    fun putManifest(chapter: DownloadedChapter) {
        val json =
            JSONObject()
                .put("albumId", chapter.albumId)
                .put("chapterId", chapter.chapterId)
                .put("title", chapter.title)
                .put("imageHost", chapter.imageHost.trimEnd('/'))
                .put("savedAt", System.currentTimeMillis())
                .put(
                    "pages",
                    JSONArray().apply {
                        chapter.pages.forEach { page ->
                            put(
                                JSONObject()
                                    .put("name", page.fileName)
                                    .put("split", page.splitCount),
                            )
                        }
                    },
                )
        writeAtomic(File(chapterDir(chapter.chapterId), MANIFEST_FILE), json.toString())
        _downloaded.update { it + chapter.chapterId }
    }

    fun loadTasks(): List<DownloadTask> {
        if (!tasksFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(tasksFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val chapterId = item.optString("chapterId")
                    val albumId = item.optString("albumId")
                    if (chapterId.isBlank() || albumId.isBlank()) continue
                    add(
                        DownloadTask(
                            albumId = albumId,
                            albumTitle = item.optString("albumTitle"),
                            chapterId = chapterId,
                            chapterName = item.optString("chapterName"),
                            chapterSort = item.optInt("chapterSort"),
                            state =
                                DownloadState.entries.firstOrNull { it.name == item.optString("state") }
                                    ?: DownloadState.Queued,
                            donePages = item.optInt("donePages"),
                            totalPages = item.optInt("totalPages"),
                            message = item.optString("message").takeIf { it.isNotBlank() },
                            updatedAt = item.optLong("updatedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveTasks(tasks: List<DownloadTask>) {
        val array =
            JSONArray().apply {
                tasks.forEach { task ->
                    put(
                        JSONObject()
                            .put("albumId", task.albumId)
                            .put("albumTitle", task.albumTitle)
                            .put("chapterId", task.chapterId)
                            .put("chapterName", task.chapterName)
                            .put("chapterSort", task.chapterSort)
                            .put("state", task.state.name)
                            .put("donePages", task.donePages)
                            .put("totalPages", task.totalPages)
                            .put("message", task.message ?: "")
                            .put("updatedAt", task.updatedAt),
                    )
                }
            }
        runCatching { writeAtomic(tasksFile, array.toString()) }
    }

    private fun scanDownloaded(): Set<String> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptySet()
        return dirs
            .filter { dir ->
                val manifest = File(dir, MANIFEST_FILE)
                if (!manifest.isFile) return@filter false
                runCatching {
                    val pages = JSONObject(manifest.readText()).optJSONArray("pages")
                    (pages?.length() ?: 0) > 0
                }.getOrDefault(false)
            }
            .map { it.name }
            .toSet()
    }

    private fun chapterDir(chapterId: String): File =
        File(root, sanitize(chapterId).ifEmpty { "chapter" })

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    // Last resort: a non-atomic write beats losing the manifest/task list.
                    target.writeText(text)
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    private companion object {
        const val DOWNLOAD_DIR = "downloads"
        const val PAGES_DIR = "pages"
        const val MANIFEST_FILE = "manifest.json"
        const val TASKS_FILE = "tasks.json"
        const val PREFS_NAME = "downloads"
        const val KEY_PAUSED = "queue_paused"
    }
}
