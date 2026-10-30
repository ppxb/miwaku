package com.ppxb.miwaku.core.data

import com.ppxb.miwaku.core.database.HistoryRecord
import com.ppxb.miwaku.core.database.ProgressRecord
import com.ppxb.miwaku.model.ChapterProgress
import com.ppxb.miwaku.model.ReadingProgress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class ProgressStore(private val library: LibraryStore) {
    private val ids = AtomicLong()
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private sealed interface Event {
        data class Begin(val session: Long) : Event
        data class Position(val session: Long, val albumId: String, val position: ReadingProgress,
            val completedAt: Long?, val at: Long = System.currentTimeMillis()) : Event
        data class Mark(val chapterId: String, val read: Boolean, val reply: CompletableDeferred<Unit>) : Event
        data class Flush(val session: Long?, val end: Boolean, val reply: CompletableDeferred<Unit>? = null) : Event
    }

    init { library.scope.launch { writeLoop() } }

    fun beginSession(): Long = ids.incrementAndGet().also { events.trySend(Event.Begin(it)) }

    fun record(session: Long, albumId: String, position: ReadingProgress, completed: Boolean = false) {
        events.trySend(Event.Position(session, albumId, position, if (completed) System.currentTimeMillis() else null))
    }

    fun flushSession(session: Long, end: Boolean = false) { events.trySend(Event.Flush(session, end)) }

    suspend fun flush() {
        val reply = CompletableDeferred<Unit>()
        events.send(Event.Flush(null, false, reply))
        reply.await()
    }

    suspend fun markRead(chapterId: String, read: Boolean) {
        val reply = CompletableDeferred<Unit>()
        events.send(Event.Mark(chapterId, read, reply))
        reply.await()
    }

    fun observeResume(albumId: String): Flow<ReadingProgress?> = library.observe { observeResume(albumId) }.map {
        it?.let { ReadingProgress(it.chapterId, it.pageIndex ?: 0, it.pageFilename, it.offsetFraction, it.readAt) }
    }.distinctUntilChanged()

    suspend fun resume(albumId: String): ReadingProgress? = library.read {
        val id = history(albumId)?.chapterId ?: return@read null
        progress(id)?.let { ReadingProgress(id, it.pageIndex ?: 0, it.pageFilename, it.offsetFraction, it.readAt) }
    }

    suspend fun chapter(chapterId: String): ReadingProgress? = library.read {
        progress(chapterId)?.let { ReadingProgress(chapterId, it.pageIndex ?: 0, it.pageFilename, it.offsetFraction, it.readAt) }
    }

    fun observeChapters(albumId: String): Flow<Map<String, ChapterProgress>> =
        library.observe { observeProgress(albumId) }.map { rows ->
            rows.associate { it.chapterId to ChapterProgress(it.chapterId, it.pageIndex, it.pageFilename, it.offsetFraction, it.readAt) }
        }.distinctUntilChanged()

    private suspend fun writeLoop() {
        var session = 0L
        var deadline = 0L
        val pending = linkedMapOf<String, Event.Position>()

        suspend fun commit() {
            if (pending.isEmpty()) return
            val batch = pending.values.toList()
            library.transaction {
                batch.forEach { event ->
                    val p = event.position
                    val previous = progress(p.chapterId) ?: ProgressRecord(p.chapterId)
                    putProgress(previous.copy(pageIndex = p.page, pageFilename = p.fileName,
                        offsetFraction = p.offsetFraction.coerceIn(0f, 1f), lastReadAt = event.at,
                        readAt = previous.readAt ?: event.completedAt))
                    putHistory((history(event.albumId) ?: HistoryRecord(event.albumId)).copy(
                        chapterId = p.chapterId, lastReadAt = event.at))
                }
            }
            pending.clear()
            deadline = 0L
            _error.value = null
        }

        while (currentCoroutineContext().isActive) {
            val event = if (pending.isEmpty()) events.receive() else
                withTimeoutOrNull((deadline - monotonicMs()).coerceAtLeast(1)) { events.receive() }
            try {
                when (event) {
                    null -> commit()
                    is Event.Begin -> { session = event.session; commit() }
                    is Event.Position -> if (event.session == session) {
                        val crossedChapter = pending.isNotEmpty() && !pending.containsKey(event.position.chapterId)
                        val completedAt = pending[event.position.chapterId]?.completedAt ?: event.completedAt
                        // Preserve event order when several chapters share a batch.
                        pending.remove(event.position.chapterId)
                        pending[event.position.chapterId] = event.copy(completedAt = completedAt)
                        if (deadline == 0L) deadline = monotonicMs() + SAVE_INTERVAL_MS
                        if (event.completedAt != null || crossedChapter) commit()
                    }
                    is Event.Mark -> {
                        commit()
                        library.transaction {
                            val previous = progress(event.chapterId) ?: ProgressRecord(event.chapterId)
                            putProgress(previous.copy(readAt = if (event.read) previous.readAt ?: System.currentTimeMillis() else null))
                        }
                        event.reply.complete(Unit)
                    }
                    is Event.Flush -> {
                        if (event.session == null || event.session == session) {
                            if (event.end) session = 0L
                            commit()
                        }
                        event.reply?.complete(Unit)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                _error.value = "阅读记录保存失败，请稍后重试"
                deadline = monotonicMs() + SAVE_INTERVAL_MS
                when (event) {
                    is Event.Mark -> event.reply.completeExceptionally(failure)
                    is Event.Flush -> event.reply?.completeExceptionally(failure)
                    else -> Unit
                }
            }
        }
    }

    private companion object { const val SAVE_INTERVAL_MS = 1_000L }
    private fun monotonicMs() = System.nanoTime() / 1_000_000
}
