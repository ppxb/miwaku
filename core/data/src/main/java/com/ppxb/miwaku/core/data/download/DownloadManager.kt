package com.ppxb.miwaku.core.data.download

import android.graphics.BitmapFactory
import com.ppxb.miwaku.core.data.ComicRepository
import com.ppxb.miwaku.core.data.settings.SettingsStore
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.await
import com.ppxb.miwaku.model.ChapterSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Serial chapter queue. A chapter is committed (manifest + downloaded flag) only after every page
 * landed on disk; interrupted chapters keep their finished pages and are resumed later.
 */
class DownloadManager(
    private val httpClient: OkHttpClient,
    private val api: ApiClient,
    private val comics: ComicRepository,
    private val store: DownloadStore,
    private val settings: SettingsStore,
    private val networkAllowed: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val persistLock = Any()
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)

    private val _tasks =
        MutableStateFlow(
            store.loadTasks().map { task ->
                if (task.state == DownloadState.Running) task.copy(state = DownloadState.Queued) else task
            },
        )
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    private val _paused = MutableStateFlow(store.isPaused())
    val isPaused: StateFlow<Boolean> = _paused.asStateFlow()

    private var worker: Job? = null

    /** Bumped on every start/pause so a cancelled worker cannot commit stale state. */
    private val generation = AtomicInteger(0)

    @Volatile
    private var paused = _paused.value

    @Volatile
    private var lastPersistAt = 0L

    init {
        // Single writer: page coroutines only enqueue a coalesced "persist" request.
        scope.launch {
            for (ignored in persistRequests) {
                store.saveTasks(_tasks.value)
            }
        }
        scope.launch { store.refreshDownloaded() }
        persist(force = true)
        if (!paused && _tasks.value.any { it.state == DownloadState.Queued }) start()
    }

    fun enqueue(albumId: String, albumTitle: String, chapters: List<ChapterSummary>) {
        if (chapters.isEmpty()) return
        _tasks.update { current ->
            val byId = current.associateBy { it.chapterId }
            val merged = current.toMutableList()
            chapters.forEach { chapter ->
                val existing = byId[chapter.id]
                when {
                    existing == null ->
                        merged +=
                            DownloadTask(
                                albumId = albumId,
                                albumTitle = albumTitle,
                                chapterId = chapter.id,
                                chapterName = chapter.name,
                                chapterSort = chapter.sort,
                            )
                    existing.state == DownloadState.Failed ||
                        (existing.state == DownloadState.Completed && store.manifest(chapter.id) == null) -> {
                        val index = merged.indexOfFirst { it.chapterId == chapter.id }
                        if (index >= 0) {
                            merged[index] =
                                merged[index].copy(
                                    state = DownloadState.Queued,
                                    donePages = 0,
                                    totalPages = 0,
                                    message = null,
                                )
                        }
                    }
                    else -> Unit
                }
            }
            merged
        }
        persist(force = true)
        start()
    }

    fun resumeAll() {
        paused = false
        _paused.value = false
        store.setPaused(false)
        start()
    }

    /** Called when a network becomes available; only does work if the queue was waiting for it. */
    fun onNetworkAvailable() {
        if (_waitingForWifi.value) start()
    }

    fun pauseAll() {
        paused = true
        _paused.value = true
        store.setPaused(true)
        generation.incrementAndGet()
        worker?.cancel()
        worker = null
        _tasks.update { list ->
            list.map { task ->
                if (task.state == DownloadState.Running) task.copy(state = DownloadState.Queued) else task
            }
        }
        persist(force = true)
    }

    /** Deletes a finished task and its files; active tasks must be paused/stopped first. */
    fun deleteFinished(chapterId: String) {
        val task = _tasks.value.firstOrNull { it.chapterId == chapterId } ?: return
        if (task.state != DownloadState.Completed && task.state != DownloadState.Failed) return
        _tasks.update { list -> list.filterNot { it.chapterId == chapterId } }
        persist(force = true)
        scope.launch {
            store.deleteChapter(chapterId)
            store.refreshDownloaded()
        }
    }

    /** Deletes every completed task and its files, leaving queued/failed tasks intact. */
    fun clearCompleted() {
        val completed = _tasks.value.filter { it.state == DownloadState.Completed }.map { it.chapterId }
        if (completed.isEmpty()) return
        _tasks.update { list -> list.filterNot { it.state == DownloadState.Completed } }
        persist(force = true)
        scope.launch {
            completed.forEach { store.deleteChapter(it) }
            store.refreshDownloaded()
        }
    }

    private fun start() {
        if (paused) return
        if (worker?.isActive == true) return
        if (_tasks.value.none { it.state == DownloadState.Queued }) return
        if (!networkAllowed()) {
            _waitingForWifi.value = true
            return
        }
        _waitingForWifi.value = false
        val runGeneration = generation.incrementAndGet()
        val job = scope.launch { runQueue(runGeneration) }
        worker = job
        job.invokeOnCompletion {
            if (worker === job) worker = null
        }
    }

    private suspend fun runQueue(runGeneration: Int) {
        runMutex.withLock {
            while (true) {
                if (paused || generation.get() != runGeneration) return@withLock
                if (!networkAllowed()) {
                    _waitingForWifi.value = true
                    return@withLock
                }
                val task = _tasks.value.firstOrNull { it.state == DownloadState.Queued } ?: break
                if (!download(task, runGeneration)) return@withLock
            }
            _waitingForWifi.value = false
        }
    }

    /** Returns false when the queue must stop (paused or waiting for Wi-Fi). */
    private suspend fun download(task: DownloadTask, runGeneration: Int): Boolean {
        if (paused || generation.get() != runGeneration) return false
        update(task.chapterId) { it.copy(state = DownloadState.Running, message = null) }
        val content =
            try {
                comics.chapter(task.albumId, task.chapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                fail(task.chapterId, e.message ?: "获取章节失败")
                return true
            }
        val pages = content.pages
        if (pages.isEmpty()) {
            fail(task.chapterId, "本章没有图片")
            return true
        }

        val already =
            pages.withIndex().count { (index, page) ->
                store.pageFile(task.chapterId, index, page.fileName).let { it.isFile && it.length() > 0 && isValidImage(it) }
            }
        update(task.chapterId) { it.copy(totalPages = pages.size, donePages = already) }
        val done = AtomicInteger(already)
        val semaphore = Semaphore(settings.downloadPageConcurrency.value.coerceIn(1, 5))

        try {
            coroutineScope {
                pages.forEachIndexed { index, page ->
                    launch {
                        if (!networkAllowed()) throw WifiRequired()
                        val target = store.pageFile(task.chapterId, index, page.fileName)
                        if (target.isFile && target.length() > 0 && isValidImage(target)) return@launch
                        semaphore.withPermit {
                            if (!networkAllowed()) throw WifiRequired()
                            downloadPage(page.url, target)
                            val finished = done.incrementAndGet()
                            update(task.chapterId) { it.copy(donePages = finished) }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (paused || generation.get() != runGeneration) return false
            store.putManifest(
                DownloadedChapter(
                    albumId = task.albumId,
                    chapterId = task.chapterId,
                    title = content.title,
                    imageHost = content.pages.firstOrNull()?.url?.toOrigin().orEmpty(),
                    pages = pages.map { DownloadedPage(it.fileName, it.splitCount) },
                ),
            )
            update(task.chapterId) {
                it.copy(
                    state = DownloadState.Completed,
                    donePages = pages.size,
                    totalPages = pages.size,
                    message = null,
                )
            }
            persist(force = true)
            return true
        } catch (e: WifiRequired) {
            update(task.chapterId) { it.copy(state = DownloadState.Queued, message = null) }
            _waitingForWifi.value = true
            persist(force = true)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            fail(task.chapterId, e.message ?: "下载失败")
            return true
        }
    }

    private suspend fun downloadPage(url: String, target: File) {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", api.userAgent())
                .build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val response = call.await()
        response.use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("image too large")
            target.parentFile?.mkdirs()
            val temp = File.createTempFile("${target.name}.", ".part", target.parentFile)
            try {
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_PAGE_BYTES) throw IOException("image too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (temp.length() <= 0) throw IOException("empty image")
                if (!isValidImage(temp)) throw IOException("not a decodable image")
                if (!temp.renameTo(target)) {
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) throw IOException("cannot store image")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun isValidImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun String.toOrigin(): String {
        val parsed = toHttpUrlOrNull() ?: return ""
        return "${parsed.scheme}://${parsed.host}"
    }

    private fun fail(chapterId: String, message: String) {
        update(chapterId) { it.copy(state = DownloadState.Failed, message = message) }
        persist(force = true)
    }

    private fun update(chapterId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { task ->
                if (task.chapterId == chapterId) {
                    transform(task).copy(updatedAt = System.currentTimeMillis())
                } else {
                    task
                }
            }
        }
        persist(force = false)
    }

    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(persistLock) {
            if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
            lastPersistAt = now
        }
        persistRequests.trySend(Unit)
    }

    private class WifiRequired : Exception()

    private companion object {
        const val MAX_PAGE_BYTES = 64L * 1024 * 1024
        const val PAGE_TIMEOUT_MS = 60_000L
        const val PERSIST_INTERVAL_MS = 1_000L
    }
}
