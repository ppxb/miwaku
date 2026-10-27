package com.ppxb.miwaku.model

data class ExploreSection(
    val id: String,
    val title: String,
    val comics: List<ComicSummary>,
)
