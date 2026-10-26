package com.ppxb.miwaku.model

data class ComicSummary(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
)

data class SearchResult(
    val total: Int,
    val comics: List<ComicSummary>,
)
