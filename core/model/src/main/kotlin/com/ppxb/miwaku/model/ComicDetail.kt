package com.ppxb.miwaku.model

data class ComicDetail(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val coverUrl: String,
    val chapters: List<ChapterSummary>,
)

data class ChapterSummary(
    val id: String,
    val name: String,
    val sort: Int,
)

data class ChapterContent(
    val albumId: String,
    val chapterId: String,
    val title: String,
    val pages: List<ReaderPage>,
)

data class ReaderPage(
    val url: String,
    val splitCount: Int,
    val fileName: String,
)
