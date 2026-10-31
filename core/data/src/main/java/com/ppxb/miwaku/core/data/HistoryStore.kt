package com.ppxb.miwaku.core.data

import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HistoryStore(private val library: LibraryStore) {
    val entries = library.observe { observeHistory() }
        .map { records -> records.map { ComicSummary(it.id, it.title, it.author, it.coverUrl) } }
        .stateIn(library.scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() { library.scope.launch { library.transaction { clearHistory() } } }
}
