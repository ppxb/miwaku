package com.ppxb.miwaku.refactor

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.model.ChapterContent
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.model.ComicDetail
import com.ppxb.miwaku.model.ComicSummary
import com.ppxb.miwaku.model.ReaderPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal class TestLibraryContext(base: Context) : ContextWrapper(base) {
    private val id = UUID.randomUUID().toString()
    private val root = File(base.cacheDir, "reader-tests/" + id).apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "databases").apply { mkdirs() }, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("reader-test-" + id + "-" + name, mode)

    fun recycle() {
        val destination = File(baseContext.filesDir, "recycle/test-artifacts").apply { mkdirs() }
        check(root.renameTo(File(destination, id)))
    }
}

internal fun testPng(width: Int = 40, height: Int = 60): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(45, 120, 210))
    return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
    finally { bitmap.recycle() }
}

/**
 * Seeds a synthetic album into the library and the download store, so tests exercise the real
 * stores without touching the network.
 */
internal suspend fun libraryFixture(chapterCount: Int): ComicDetail {
    AppGraph.library.awaitReady()
    AppGraph.settings.setWifiOnlyPrefetch(false)
    AppGraph.settings.setDefaultReadingMode(ReadingMode.Webtoon)
    val id = "reader-fixture-" + System.nanoTime()
    val chapters = (1..chapterCount).map { ChapterSummary(id + "-" + it, "测试章节 " + it, it) }
    val detail =
        ComicDetail(
            id,
            "阅读器验证 · 合成内容",
            "本地测试",
            "用于验证逐章进度与预取，不含线上内容。",
            emptyList(),
            "",
            chapters,
        )
    AppGraph.library.saveDetail(detail)
    for (chapter in chapters) {
        val pages =
            (0..1).map { index ->
                val name = "$index.png"
                val file = AppGraph.downloadStore.pageFile(chapter.id, index, name)
                file.parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(360, 1800, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (index == 0) Color.rgb(220, 237, 250) else Color.rgb(232, 246, 224))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 50, 70); textSize = 24f }
                canvas.drawText("CHAPTER " + chapter.sort + " / PAGE " + (index + 1), 16f, 64f, paint)
                canvas.drawText("END OF PAGE " + (index + 1), 16f, 1760f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                ReaderPage("https://example.invalid/media/photos/" + chapter.id + "/" + name, 0, name)
            }
        AppGraph.library.saveChapter(ChapterContent(id, chapter.id, chapter.name, pages), "https://example.invalid")
    }
    AppGraph.library.recordVisit(ComicSummary(id, detail.title, detail.author, detail.coverUrl))
    return detail
}
