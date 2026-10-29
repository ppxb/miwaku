package com.ppxb.miwaku.core.data.download

enum class DownloadState { Queued, Running, Completed, Failed }

data class DownloadTask(
    val albumId: String,
    val albumTitle: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSort: Int = 0,
    val state: DownloadState = DownloadState.Queued,
    val donePages: Int = 0,
    val totalPages: Int = 0,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (totalPages <= 0) 0f else donePages.toFloat() / totalPages
}
