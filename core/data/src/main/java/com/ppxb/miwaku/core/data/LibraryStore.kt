package com.ppxb.miwaku.core.data

import android.content.Context
import androidx.room.withTransaction
import com.ppxb.miwaku.core.database.*
import com.ppxb.miwaku.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

enum class LibraryStartup { Loading, Ready, Failed }

/** The application's durable catalog. Legacy files are inputs, never a second writer. */
class LibraryStore(private val context: Context) : java.io.Closeable {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val database = LibraryDatabase.open(context)
    internal val dao = database.library()
    private var ready = CompletableDeferred<Unit>()
    private val _startup = MutableStateFlow(LibraryStartup.Loading)
    val startup = _startup.asStateFlow()

    init { initialize() }

    @Synchronized
    fun retryInitialization() {
        if (_startup.value != LibraryStartup.Failed) return
        ready = CompletableDeferred()
        _startup.value = LibraryStartup.Loading
        initialize()
    }

    private fun initialize() {
        val completion = ready
        scope.launch {
            try {
                LegacyLibraryImport(context, database).run()
                database.withTransaction { dao.recoverRunning() }
                _startup.value = LibraryStartup.Ready
                completion.complete(Unit)
            } catch (error: Exception) {
                completion.completeExceptionally(error)
                _startup.value = LibraryStartup.Failed
            }
        }
    }

    suspend fun awaitReady() = ready.await()
    override fun close() { scope.cancel(); database.close() }

    internal suspend fun <T> read(block: LibraryDao.() -> T): T {
        awaitReady()
        return withContext(Dispatchers.IO) { dao.block() }
    }

    internal suspend fun <T> transaction(block: LibraryDao.() -> T): T {
        awaitReady()
        return withContext(Dispatchers.IO) { database.withTransaction { dao.block() } }
    }

    internal fun <T> observe(block: LibraryDao.() -> Flow<T>): Flow<T> = flow {
        awaitReady()
        emitAll(dao.block().distinctUntilChanged())
    }

    suspend fun detail(id: String): Pair<ComicDetail, Long>? = read {
        val comic = comic(id) ?: return@read null
        val chapters = chapters(id)
        if (chapters.isEmpty()) return@read null
        val tags = JSONArray(comic.tags)
        ComicDetail(comic.id, comic.title, comic.author, comic.description,
            List(tags.length()) { tags.getString(it) }, comic.coverUrl,
            chapters.map { ChapterSummary(it.id, it.name, it.sort) }) to comic.fetchedAt
    }

    suspend fun saveDetail(detail: ComicDetail) = transaction {
        putComic(ComicRecord(detail.id, detail.title, detail.author, detail.description,
            JSONArray(detail.tags).toString(), detail.coverUrl, System.currentTimeMillis(), true))
        detail.chapters.forEach { chapter ->
            seedChapter(ChapterRecord(chapter.id, detail.id, chapter.name, chapter.sort))
            updateChapterSummary(chapter.id, chapter.name, chapter.sort)
        }
    }

    suspend fun chapter(id: String, preferredHost: String?): ChapterContent? = read {
        val chapter = chapter(id)?.takeIf { it.hasManifest } ?: return@read null
        val pages = pages(id)
        if (pages.isEmpty()) return@read null
        val host = preferredHost?.takeIf { it.isNotBlank() } ?: chapter.imageHost
        ChapterContent(chapter.albumId, id, chapter.name, pages.map {
            ReaderPage("${host.trimEnd('/')}/media/photos/$id/${it.fileName}", it.splitCount, it.fileName)
        })
    }

    suspend fun saveChapter(content: ChapterContent, host: String) = transaction {
        seedComic(ComicRecord(content.albumId))
        seedChapter(ChapterRecord(content.chapterId, content.albumId, content.title))
        clearPages(content.chapterId)
        putPages(content.pages.mapIndexed { index, page ->
            PageRecord(content.chapterId, index, page.fileName, page.splitCount)
        })
        setManifest(content.chapterId, host)
    }
}
