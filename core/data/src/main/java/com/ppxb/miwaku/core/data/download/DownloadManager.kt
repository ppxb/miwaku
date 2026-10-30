package com.ppxb.miwaku.core.data.download

import com.ppxb.miwaku.core.data.ComicRepository
import com.ppxb.miwaku.core.data.ProgressStore
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.image.PagePriority
import com.ppxb.miwaku.core.data.settings.SettingsStore
import com.ppxb.miwaku.core.network.ApiFailure
import com.ppxb.miwaku.model.ChapterSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One persistent chapter queue. Room is authoritative; a wake-up cannot be lost at worker exit. */
class DownloadManager(
    private val pageStore: PageImageStore,
    private val progress: ProgressStore,
    private val comics: ComicRepository,
    private val store: DownloadStore,
    private val settings: SettingsStore,
    private val networkAllowed: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    val tasks = store.tasks
    private val _paused = MutableStateFlow(store.isPaused())
    val isPaused = _paused.asStateFlow()
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi = _waitingForWifi.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeChapter: String? = null

    init {
        scope.launch {
            var reconciled = false
            for (ignored in wake) {
                try {
                    store.awaitReady()
                    if (!reconciled) { store.refreshDownloaded(); reconciled = true }
                    runQueue()
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { _error.value = "下载状态保存失败，请重试"; pauseAll() }
            }
        }
        scope.launch {
            for (action in commands) {
                try { store.awaitReady(); control.withLock { action() }; _error.value = null }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { _error.value = "下载操作失败，原文件已保留" }
            }
        }
        wake.trySend(Unit)
    }

    fun enqueue(albumId: String, albumTitle: String, chapters: List<ChapterSummary>) = command {
        store.enqueue(albumId, albumTitle, chapters)
        wake.trySend(Unit)
    }

    fun enqueueUnread(albumId: String, title: String) = command {
        progress.flush()
        store.enqueue(albumId, title, null, unreadOnly = true)
        wake.trySend(Unit)
    }

    fun pauseAll() {
        _paused.value = true
        store.setPaused(true)
        activeJob?.cancel()
    }

    fun resumeAll() {
        command {
            if (activeJob?.isActive != true) store.recoverRunning()
            _paused.value = false
            store.setPaused(false)
            wake.trySend(Unit)
        }
    }

    fun onNetworkAvailable() {
        if (!networkAllowed()) {
            _waitingForWifi.value = true
            activeJob?.cancel()
        }
        wake.trySend(Unit)
    }

    fun cancel(chapterId: String) = command {
        if (store.state(chapterId) !in listOf(DownloadState.Queued, DownloadState.Running)) return@command
        if (activeChapter == chapterId) activeJob?.cancelAndJoin()
        store.deleteChapter(chapterId)
        wake.trySend(Unit)
    }

    fun deleteFinished(chapterId: String) = command {
        if (store.state(chapterId) in listOf(DownloadState.Completed, DownloadState.Failed)) store.deleteChapter(chapterId)
    }

    fun clearCompleted() = command {
        store.completedTasks().forEach { store.deleteChapter(it.chapterId) }
    }

    fun trimToStorageLimit() = command {
        val limit = settings.downloadLimitMb.value * 1024L * 1024L
        if (limit <= 0L) return@command
        var bytes = store.downloadSizeBytes()
        for (task in store.completedTasks().sortedBy { it.updatedAt }) {
            if (bytes <= limit) break
            val size = store.chapterSizeBytes(task.chapterId)
            store.deleteChapter(task.chapterId)
            bytes -= size
        }
    }

    private fun command(action: suspend () -> Unit) {
        commands.trySend(action)
    }

    private suspend fun runQueue() {
        while (true) {
            val job = control.withLock {
                if (_paused.value) return
                if (!networkAllowed()) { _waitingForWifi.value = true; return }
                _waitingForWifi.value = false
                val task = store.nextQueued() ?: return
                activeChapter = task.chapterId
                scope.async(start = CoroutineStart.LAZY) { download(task) }.also {
                    activeJob = it
                    it.start()
                }
            }
            try { job.await() }
            catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
            finally {
                control.withLock { if (activeJob === job) { activeJob = null; activeChapter = null } }
            }
        }
    }

    private suspend fun download(task: DownloadTask) {
        try {
            if (_paused.value || !networkAllowed()) return
            store.update(task.chapterId, DownloadState.Running)
            val content = comics.chapter(task.albumId, task.chapterId)
            if (content.pages.isEmpty()) throw IOException("empty chapter")
            store.update(task.chapterId, totalPages = content.pages.size)
            val slots = Semaphore(settings.downloadPageConcurrency.value.coerceIn(1, 5))
            val failures = AtomicInteger()
            val missing = AtomicInteger()
            supervisorScope {
                content.pages.mapIndexed { index, page ->
                    async {
                        slots.withPermit {
                            try {
                                if (!networkAllowed()) throw WifiRequired()
                                val image = PageImage(task.chapterId, page.fileName, index, page.splitCount, page.url)
                                val target = store.pageFile(task.chapterId, index, page.fileName)
                                var lastError: Exception? = null
                                for (attempt in 0..2) {
                                    try {
                                        val source = pageStore.prepare(image, PagePriority.Download)
                                        if (source.absolutePath != target.absolutePath) {
                                            copyPage(source, target)
                                            // The offline copy owns the bytes now; keep a single file per page.
                                            pageStore.releaseCachedCopy(image)
                                        }
                                        store.recordPage(task.chapterId, index, page.fileName)
                                        lastError = null
                                        break
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) {
                                        // A page the server no longer serves is not worth three attempts.
                                        if (failure.isPageMissing()) throw failure
                                        lastError = failure
                                        if (attempt < 2) delay(300L * (attempt + 1))
                                    }
                                }
                                if (lastError != null) throw lastError
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) {
                                if (failure.isPageMissing()) missing.incrementAndGet() else failures.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) throw WifiRequired()
            if (failures.get() > 0) {
                store.update(task.chapterId, DownloadState.Failed, message = "部分页面下载失败，可重试续传")
                return
            }
            if (missing.get() > 0) {
                store.update(task.chapterId, DownloadState.Failed, message = "部分页面已失效，无法离线")
                return
            }
            store.putManifest(DownloadedChapter(task.albumId, task.chapterId, content.title, "",
                content.pages.map { DownloadedPage(it.fileName, it.splitCount) }))
            trimToStorageLimit()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { store.requeueRunning(task.chapterId) }
            throw cancel
        } catch (_: WifiRequired) {
            _waitingForWifi.value = true
            store.update(task.chapterId, DownloadState.Queued)
        } catch (_: Exception) {
            store.update(task.chapterId, DownloadState.Failed, message = "下载失败，请重试")
        }
    }

    private suspend fun copyPage(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(target.name, ".part", target.parentFile)
        try {
            source.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (temporary.length() == 0L) throw IOException("empty page")
            if (!temporary.renameTo(target)) throw IOException("cannot commit page")
        } finally {
            if (temporary.exists()) {
                val trash = File(target.parentFile, ".recycle").apply { mkdirs() }
                temporary.renameTo(File(trash, temporary.name))
            }
        }
    }

    private class WifiRequired : IOException()

    /** 404/410 means the page itself is gone; retrying or rotating the host cannot help. */
    private fun Throwable.isPageMissing(): Boolean = this is ApiFailure.Http && (code == 404 || code == 410)
    fun close() { scope.cancel() }
}
