package com.ppxb.miwaku.ui.screen.category

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppxb.miwaku.R
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

/** Category (`c`) values the ranking endpoint accepts; `0` is the unfiltered "latest" feed. */
internal enum class RankingCategory(
    val value: String,
    @StringRes val label: Int,
) {
    Latest("0", R.string.category_filter_latest),
    Doujin("doujin", R.string.category_filter_doujin),
    Single("single", R.string.category_filter_single),
    Short("short", R.string.category_filter_short),
    Another("another", R.string.category_filter_another),
    Hanman("hanman", R.string.category_filter_hanman),
    Meiman("meiman", R.string.category_filter_meiman),
    Cosplay("another_cosplay", R.string.category_filter_cosplay),
    ThreeD("3D", R.string.category_filter_3d),
    Team("禁漫汉化组", R.string.category_filter_team),
}

/** Order (`o`) values: one feed order plus the four ranked periods. */
internal enum class RankingOrder(
    val value: String,
    @StringRes val label: Int,
) {
    New("new", R.string.category_order_new),
    Likes("tf", R.string.category_order_likes),
    Total("mv", R.string.category_order_total),
    Monthly("mv_m", R.string.category_order_month),
    Weekly("mv_w", R.string.category_order_week),
    Daily("mv_t", R.string.category_order_day),
}

internal sealed interface CategoryUiState {
    data object Loading : CategoryUiState

    data class Content(val comics: List<ComicSummary>) : CategoryUiState

    data class Failed(val message: String) : CategoryUiState
}

internal class CategoryViewModel : ViewModel() {
    var category by mutableStateOf(RankingCategory.Latest)
        private set

    var order by mutableStateOf(RankingOrder.New)
        private set

    private val _state = MutableStateFlow<CategoryUiState>(CategoryUiState.Loading)
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectCategory(value: RankingCategory) {
        if (category == value) return
        category = value
        load()
    }

    fun selectOrder(value: RankingOrder) {
        if (order == value) return
        order = value
        load()
    }

    fun load() {
        val selectedCategory = category
        val selectedOrder = order
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                _state.value = CategoryUiState.Loading
                try {
                    val comics =
                        withContext(Dispatchers.IO) {
                            AppGraph.comics.ranking(selectedOrder.value, selectedCategory.value)
                        }
                    _state.value = CategoryUiState.Content(comics)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = CategoryUiState.Failed("网络请求失败，请重试")
                }
            }
    }
}
