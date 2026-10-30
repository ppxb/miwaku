package com.ppxb.miwaku.ui.screen.reader

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.image.PagePriority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal data class PageDemand(val image: PageImage, val priority: PagePriority)

/** Keep intersecting page Jobs alive; shared admission, concurrency and priority live in the store. */
internal class ReaderPrefetcher(
    private val scope: CoroutineScope,
    private val store: PageImageStore,
    private val onPageReady: (PageImage) -> Unit,
    private val onPageFailed: (PageImage) -> Unit,
) {
    private data class Entry(val priority: MutableStateFlow<PagePriority>, val job: Job)
    private val entries = linkedMapOf<String, Entry>()

    fun update(demands: List<PageDemand>) {
        val wanted = demands.associateBy { it.image.cacheKey }
        val removed = entries.keys.filter { it !in wanted }
        removed.forEach { entries.remove(it)?.job?.cancel() }
        wanted.forEach { (key, demand) ->
            val existing = entries[key]
            if (existing != null) {
                existing.priority.value = demand.priority
            } else {
                val priority = MutableStateFlow(demand.priority)
                val job = scope.launch {
                    try { store.prepare(demand.image, priority); onPageReady(demand.image) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { onPageFailed(demand.image) }
                }
                entries[key] = Entry(priority, job)
            }
        }
    }

    fun retry(key: String) { entries.remove(key)?.job?.cancel() }
    fun cancel() { entries.values.forEach { it.job.cancel() }; entries.clear() }
}
