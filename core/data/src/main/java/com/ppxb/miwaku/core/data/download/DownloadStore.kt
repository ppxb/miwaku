package com.ppxb.miwaku.core.data.download

import android.content.Context
import com.ppxb.miwaku.core.data.LibraryStore
import com.ppxb.miwaku.core.database.*
import com.ppxb.miwaku.model.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class DownloadedPage(val fileName: String, val splitCount: Int)
data class DownloadedChapter(
    val albumId: String, val chapterId: String, val title: String,
    val imageHost: String, val pages: List<DownloadedPage>,
) {
    fun toContent(host: String) = ChapterContent(albumId, chapterId, title, pages.map {
        ReaderPage(host.trimEnd('/') + "/media/photos/" + chapterId + "/" + it.fileName, it.splitCount, it.fileName)
    })
}

/** Room owns indices; original files retain their existing locations. */
class DownloadStore(private val context: Context, private val library: LibraryStore) {
    private val root = File(context.filesDir, "downloads")
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val tasks = library.observe { observeTasks() }.map { list -> list.map { row ->
        val t = row.task
        DownloadTask(t.albumId, t.albumTitle, t.chapterId, t.chapterName, t.chapterSort,
            DownloadState.entries.firstOrNull { it.name == t.state } ?: DownloadState.Failed,
            row.donePages, t.totalPages, t.message, t.updatedAt)
    } }.shareIn(library.scope, SharingStarted.Eagerly, replay = 1)
    val downloaded = library.observe { observeDownloaded() }.map { it.toSet() }
        .stateIn(library.scope, SharingStarted.Eagerly, emptySet())

    suspend fun awaitReady() = library.awaitReady()
    suspend fun state(chapterId: String): DownloadState? = library.read {
        task(chapterId)?.state?.let { name -> DownloadState.entries.firstOrNull { it.name == name } }
    }
    suspend fun completedTasks(): List<TaskRecord> = library.read { tasks().filter { it.state == "Completed" } }
    fun isPaused() = prefs.getBoolean("queue_paused", false)
    fun setPaused(value: Boolean) { prefs.edit().putBoolean("queue_paused", value).apply() }

    fun pageFile(chapterId: String, index: Int, fileName: String): File =
        File(File(chapterDir(chapterId), "pages"), index.toString().padStart(4, '0') + "_" + safe(fileName))

    suspend fun manifest(chapterId: String): DownloadedChapter? = library.read {
        if (task(chapterId)?.state != "Completed") return@read null
        val c = chapter(chapterId) ?: return@read null
        DownloadedChapter(c.albumId, chapterId, c.name, c.imageHost,
            pages(chapterId).map { DownloadedPage(it.fileName, it.splitCount) })
    }

    /** Writes a whole batch of finished pages in one transaction; the queue records progress in
     *  groups, so a 150-page chapter is a handful of writes instead of 150. */
    suspend fun recordPages(chapterId: String, pages: List<Pair<Int, String>>) {
        if (pages.isEmpty()) return
        val records =
            pages.map { (index, fileName) ->
                val file = pageFile(chapterId, index, fileName)
                DownloadPageRecord(
                    chapterId,
                    index,
                    fileName,
                    file.relativeTo(context.filesDir).path,
                    file.length(),
                )
            }
        library.transaction {
            if (task(chapterId) != null) records.forEach { putDownloadedPage(it) }
        }
    }

    suspend fun putManifest(chapter: DownloadedChapter) {
        withContext(Dispatchers.IO) {
            // A process killed mid-copy leaves its temp file next to the page; nothing else sweeps
            // this directory, so completion is the one place that can drop the leftovers.
            val expected =
                chapter.pages.mapIndexed { index, page ->
                    pageFile(chapter.chapterId, index, page.fileName).name
                }.toSet()
            File(chapterDir(chapter.chapterId), "pages").listFiles()?.forEach { file ->
                if (file.isFile && file.name !in expected) file.delete()
            }
            chapter.pages.forEachIndexed { index, page ->
                if (!valid(pageFile(chapter.chapterId, index, page.fileName))) throw IOException("offline page missing")
            }
        }
        library.transaction {
            val existing = task(chapter.chapterId) ?: return@transaction
            putTask(existing.copy(state = "Completed", totalPages = chapter.pages.size,
                message = null, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun enqueue(albumId: String, title: String, requested: List<ChapterSummary>?, unreadOnly: Boolean = false) =
        library.transaction {
            val order = lastQueueOrder()
            val selected = if (unreadOnly) unread(albumId).map { ChapterSummary(it.id, it.name, it.sort) }
                else requested.orEmpty()
            selected.forEachIndexed { index, chapter ->
                val existing = task(chapter.id)
                if (existing?.state in listOf("Queued", "Running", "Completed")) return@forEachIndexed
                seedComic(ComicRecord(albumId, title))
                seedChapter(ChapterRecord(chapter.id, albumId, chapter.name, chapter.sort))
                putTask(existing?.copy(state = "Queued", message = null, updatedAt = System.currentTimeMillis())
                    ?: TaskRecord(chapter.id, albumId, title, chapter.name, chapter.sort,
                        updatedAt = System.currentTimeMillis(), queueOrder = order + index + 1))
            }
        }

    suspend fun nextQueued(): DownloadTask? = library.read {
        val t = tasks().firstOrNull { it.state == "Queued" } ?: return@read null
        DownloadTask(t.albumId, t.albumTitle, t.chapterId, t.chapterName, t.chapterSort,
            DownloadState.Queued, downloadedPages(t.chapterId).size, t.totalPages, t.message, t.updatedAt)
    }

    /**
     * Authoritative queue state for the WorkManager fallback: the task flow may not have emitted
     * yet when a worker starts a fresh process, so it must not be trusted for this decision.
     */
    suspend fun hasUnfinished(): Boolean = library.read {
        tasks().any { it.state == "Queued" || it.state == "Running" }
    }

    suspend fun update(chapterId: String, state: DownloadState? = null, totalPages: Int? = null, message: String? = null) =
        library.transaction {
            val old = task(chapterId) ?: return@transaction
            putTask(old.copy(state = state?.name ?: old.state, totalPages = totalPages ?: old.totalPages,
                message = message, updatedAt = System.currentTimeMillis()))
        }

    suspend fun requeueRunning(chapterId: String) = library.transaction {
        task(chapterId)?.takeIf { it.state == "Running" }?.let { putTask(it.copy(state = "Queued")) }
    }
    suspend fun recoverRunning() = library.transaction { recoverRunning() }

    /** Reconcile trusted completion records at startup, not on every UI query. */
    suspend fun refreshDownloaded() {
        val complete = library.read { tasks().filter { it.state == "Completed" } }
        for (task in complete) {
            val pages = library.read { pages(task.chapterId) }
            val missing = withContext(Dispatchers.IO) {
                pages.filterNot { valid(pageFile(it.chapterId, it.index, it.fileName)) }
            }
            if (pages.isEmpty() || missing.isNotEmpty()) {
                library.transaction { missing.forEach { removeDownloadedPage(it.chapterId, it.index) } }
                update(task.chapterId, DownloadState.Failed, message = "离线文件不完整，可重试恢复")
            }
        }
    }

    /** User removal deletes the chapter outright: no recycle directory, same as the reference apps. */
    suspend fun deleteChapter(chapterId: String) {
        withContext(Dispatchers.IO) { chapterDir(chapterId).deleteRecursively() }
        library.transaction {
            clearDownloadedPages(chapterId)
            removeTask(chapterId)
        }
    }

    fun chapterSizeBytes(chapterId: String): Long = chapterDir(chapterId).sizeBytes()
    fun downloadSizeBytes(): Long = root.listFiles()?.filter { it.isDirectory }?.sumOf { it.sizeBytes() } ?: 0L
    private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun chapterDir(id: String) = File(root, safe(id))
    private fun safe(value: String) = value.filter { it.isLetterOrDigit() || it in "-_." }.ifEmpty { "page" }
    /**
     * Existence check only: pages are written atomically and validated before the copy, so
     * decoding every downloaded page at startup would cost seconds for no extra signal.
     */
    private fun valid(file: File): Boolean = file.isFile && file.length() > 0L
}
