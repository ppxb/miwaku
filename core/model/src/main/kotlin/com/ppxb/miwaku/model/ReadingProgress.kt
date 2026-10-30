package com.ppxb.miwaku.model

data class ReadingProgress(
    val chapterId: String,
    val page: Int,
    val fileName: String? = null,
    val offsetFraction: Float = 0f,
    val readAt: Long? = null,
)

data class ChapterProgress(
    val chapterId: String,
    val page: Int? = null,
    val fileName: String? = null,
    val offsetFraction: Float = 0f,
    val readAt: Long? = null,
) {
    val isRead: Boolean get() = readAt != null
    val hasStarted: Boolean get() = page != null
}
