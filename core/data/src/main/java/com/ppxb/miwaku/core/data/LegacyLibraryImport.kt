package com.ppxb.miwaku.core.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.ppxb.miwaku.core.database.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Read/validate outside the transaction; commit the import marker with all imported rows. */
internal class LegacyLibraryImport(private val context: Context, private val database: LibraryDatabase) {
    private val comics = linkedMapOf<String, ComicRecord>()
    private val chapters = linkedMapOf<String, ChapterRecord>()
    private val pages = linkedMapOf<Pair<String, Int>, PageRecord>()
    private val progress = linkedMapOf<String, ProgressRecord>()
    private val history = linkedMapOf<String, HistoryRecord>()
    private val tasks = linkedMapOf<String, TaskRecord>()
    private val files = mutableListOf<DownloadPageRecord>()

    suspend fun run() {
        val dao = database.library()
        if (dao.migration(MARKER) != null) return
        importSummaries("favorites")
        importSummaries("history")
        jsonFiles(File(context.cacheDir, "reader_details/v1")).forEach { file ->
            val (comic, catalog) = runCatching { parseDetail(file) }.getOrNull() ?: return@forEach
            comics[comic.id] = comic
            catalog.forEach { chapters[it.id] = it }
        }
        val root = File(context.filesDir, "downloads")
        val taskFile = File(root, "tasks.json")
        if (taskFile.isFile) JSONArray(taskFile.readText()).objects().forEachIndexed { index, json ->
            val cid = json.getString("chapterId")
            val aid = json.getString("albumId")
            comics.putIfAbsent(aid, ComicRecord(aid, json.optString("albumTitle")))
            chapters.putIfAbsent(cid, ChapterRecord(cid, aid, json.optString("chapterName"), json.optInt("chapterSort")))
            tasks[cid] = TaskRecord(cid, aid, json.optString("albumTitle"), json.optString("chapterName"),
                json.optInt("chapterSort"), json.optString("state", "Queued").let {
                    if (it == "Running" || it == "Queued") "Queued" else "Failed"
                }, json.optInt("totalPages"), null, json.optLong("updatedAt"), index.toLong())
        }
        val raw = context.getSharedPreferences("progress", Context.MODE_PRIVATE).getString("entries", null)
        if (raw != null) {
            val json = JSONObject(raw)
            json.keys().forEach { aid ->
                val entry = json.getJSONObject(aid)
                val cid = entry.getString("chapterId")
                if (aid.isBlank() || cid.isBlank()) return@forEach
                comics.putIfAbsent(aid, ComicRecord(aid))
                chapters.putIfAbsent(cid, ChapterRecord(cid, aid))
                progress[cid] = ProgressRecord(cid, entry.optInt("page").coerceAtLeast(0))
                history[aid] = (history[aid] ?: HistoryRecord(aid)).copy(chapterId = cid)
            }
        }
        // Resolve relations from durable tasks/progress before importing optional manifests.
        jsonFiles(File(context.cacheDir, "reader_manifests/v1")).forEach { file ->
            runCatching { importManifest(JSONObject(file.readText()), file.nameWithoutExtension, false) }
        }
        root.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }?.forEach { dir ->
            val file = File(dir, "manifest.json")
            if (file.isFile) {
                try { importManifest(JSONObject(file.readText()), dir.name, true) }
                catch (_: Exception) { tasks[dir.name]?.let { tasks[dir.name] = it.copy(state = "Failed", message = "旧下载清单损坏，可重试恢复") } }
            }
        }
        database.withTransaction {
            if (dao.migration(MARKER) != null) return@withTransaction
            comics.values.forEach(dao::putComic)
            chapters.values.forEach { dao.seedChapter(it); if (it.hasManifest) dao.setManifest(it.id, it.imageHost) }
            dao.putPages(pages.values.toList())
            progress.values.forEach(dao::putProgress)
            history.values.forEach(dao::putHistory)
            tasks.values.forEach(dao::putTask)
            files.forEach(dao::putDownloadedPage)
            dao.migrated(MigrationRecord(MARKER))
        }
    }

    private fun importSummaries(name: String) {
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("entries", null) ?: return
        val list = JSONArray(raw).objects()
        list.forEachIndexed { index, json ->
            val id = json.getString("id")
            if (id.isBlank()) return@forEachIndexed
            comics[id] = ComicRecord(id, json.optString("title"), json.optString("author"), coverUrl = json.optString("coverUrl"))
            if (name == "history") history[id] = HistoryRecord(id, legacyOrder = list.size - index)
        }
    }

    private fun parseDetail(file: File): Pair<ComicRecord, List<ChapterRecord>> {
        val json = JSONObject(file.readText())
        val id = json.getString("id").also { require(it.isNotBlank()) }
        val catalog = (json.optJSONArray("chapters") ?: JSONArray()).objects().map { chapter ->
            ChapterRecord(chapter.getString("id").also { require(it.isNotBlank()) }, id,
                chapter.optString("name"), chapter.optInt("sort"))
        }
        return ComicRecord(id, json.optString("title"), json.optString("author"),
            json.optString("description"), (json.optJSONArray("tags") ?: JSONArray()).toString(),
            json.optString("coverUrl"), file.lastModified(), true) to catalog
    }

    private fun importManifest(json: JSONObject, cid: String, downloaded: Boolean) {
        val aid = json.optString(if (downloaded) "albumId" else "seriesId").takeIf { it.isNotBlank() }
            ?: chapters[cid]?.albumId ?: return
        val entries = (json.optJSONArray("pages") ?: JSONArray()).objects()
        if (entries.isEmpty()) return
        val chapter = chapters[cid] ?: ChapterRecord(cid, aid, json.optString("title"))
        val parsedPages = entries.mapIndexed { index, page ->
            PageRecord(cid, index, page.getString("name").also { require(it.isNotBlank()) }, page.optInt("split"))
        }
        val parsedFiles = if (!downloaded) emptyList() else parsedPages.mapNotNull { page ->
                val file = File(context.filesDir, "downloads/${safe(cid)}/pages/${page.index.toString().padStart(4,'0')}_${safe(page.fileName)}")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (file.isFile && file.length() > 0) BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    DownloadPageRecord(cid, page.index, page.fileName, file.relativeTo(context.filesDir).path, file.length())
                } else null
        }
        // Publish this complete parsed manifest only after every field has been validated.
        comics.putIfAbsent(aid, ComicRecord(aid))
        chapters[cid] = chapter.copy(imageHost = json.optString("imageHost"), hasManifest = true)
        pages.keys.removeAll { it.first == cid }
        parsedPages.forEach { pages[cid to it.index] = it }
        if (downloaded) {
            files.removeAll { it.chapterId == cid }
            files += parsedFiles
            val complete = parsedFiles.size == parsedPages.size
            val task = tasks[cid] ?: TaskRecord(cid, aid, comics[aid]?.title.orEmpty(), chapter.name, chapter.sort,
                queueOrder = tasks.size.toLong())
            tasks[cid] = task.copy(state = if (complete) "Completed" else "Failed", totalPages = entries.size,
                message = if (complete) null else "离线文件不完整，可重试恢复")
        }
    }

    private fun jsonFiles(dir: File) = dir.listFiles { file -> file.isFile && file.extension == "json" }?.toList().orEmpty()
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun safe(value: String) = value.filter { it.isLetterOrDigit() || it in "-_." }

    private companion object { const val MARKER = "legacy-library-v1" }
}
