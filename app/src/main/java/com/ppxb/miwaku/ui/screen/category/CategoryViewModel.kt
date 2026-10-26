package com.ppxb.miwaku.ui.screen.category

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.model.ComicSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class RankingOrder(val value: String) {
    Latest("mr"),
    Popular("mv"),
}

internal sealed interface CategoryUiState {
    data object Loading : CategoryUiState

    data class Content(val comics: List<ComicSummary>) : CategoryUiState

    data class Failed(val message: String) : CategoryUiState
}

internal class CategoryViewModel : ViewModel() {
    var order by mutableStateOf(RankingOrder.Latest)
        private set

    private val _state = MutableStateFlow<CategoryUiState>(CategoryUiState.Loading)
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun select(order: RankingOrder) {
        if (this.order == order) return
        this.order = order
        load()
    }

    fun load() {
        val selected = order
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                _state.value = CategoryUiState.Loading
                try {
                    val comics = withContext(Dispatchers.IO) { AppGraph.comics.ranking(selected.value) }
                    _state.value = CategoryUiState.Content(comics)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = CategoryUiState.Failed("网络请求失败，请重试")
                }
            }
    }
}
