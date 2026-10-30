package com.ppxb.miwaku.refactor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.model.*
import com.ppxb.miwaku.ui.screen.reader.ReaderUiState
import com.ppxb.miwaku.ui.screen.reader.ReaderViewModel
import com.ppxb.miwaku.ui.screen.reader.ReaderWindow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSessionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    internal suspend fun fixture(chapterCount: Int): ComicDetail {
        AppGraph.library.awaitReady()
        AppGraph.settings.setWifiOnlyPrefetch(false)
        AppGraph.settings.setDefaultReadingMode(ReadingMode.Webtoon)
        val id = "reader-fixture-" + System.nanoTime()
        val chapters = (1..chapterCount).map { ChapterSummary(id + "-" + it, "测试章节 " + it, it) }
        val detail = ComicDetail(id, "阅读器验证 · 合成内容", "本地测试", "用于验证逐章进度与预取，不含线上内容。", emptyList(), "", chapters)
        AppGraph.library.saveDetail(detail)
        for (chapter in chapters) {
            val pages = (0..1).map { index ->
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

    private fun create(detail: ComicDetail): Pair<ReaderViewModel, ViewModelStore> = main {
        val store = ViewModelStore()
        val vm = ReaderViewModel(SavedStateHandle(mapOf("albumId" to detail.id, "chapterId" to detail.chapters.first().id, "page" to 0)))
        store.put("reader", vm)
        vm to store
    }

    private suspend fun window(vm: ReaderViewModel, contains: String? = null): ReaderWindow = withTimeout(10_000) {
        vm.state.filterIsInstance<ReaderUiState.Content>().first { state ->
            contains == null || state.window.chapters.any { it.chapterId == contains }
        }.window
    }

    private fun report(vm: ReaderViewModel, chapterId: String, local: Int, end: Boolean = false, displayed: Boolean = false) = main {
        val w = (vm.state.value as ReaderUiState.Content).window
        val global = w.images.indexOfFirst { it.chapterId == chapterId && it.index == local }
        check(global >= 0)
        val image = w.images[global]
        vm.onViewport(w.id, w.revision, listOf(global), global,
            "p:" + chapterId + ":" + local + ":" + image.fileName, 0, 0f,
            if (end) setOf(global) else emptySet(), scrolling = false, canRecord = true)
        if (displayed) vm.imageDisplayed(w.id, image, true)
    }

    @Test fun chapterCompletesOnlyWhenItsVisibleLastImageSucceeds() = runBlocking {
        val detail = fixture(2)
        val (vm, store) = create(detail)
        try {
            window(vm)
            val chapter = detail.chapters.first().id
            report(vm, chapter, 1, end = true)
            AppGraph.progress.flush()
            assertNull(AppGraph.progress.chapter(chapter))
            report(vm, chapter, 1, end = true, displayed = true)
            AppGraph.progress.flush()
            assertNotNull(AppGraph.progress.chapter(chapter)!!.readAt)
            AppGraph.progress.markRead(chapter, false)
            report(vm, chapter, 1, end = true, displayed = true)
            AppGraph.progress.flush()
            assertNull(AppGraph.progress.chapter(chapter)!!.readAt)
            report(vm, chapter, 0, displayed = true)
            report(vm, chapter, 1, end = true, displayed = true)
            AppGraph.progress.flush()
            assertNotNull(AppGraph.progress.chapter(chapter)!!.readAt)
        } finally { main { store.clear() }; AppGraph.progress.flush() }
    }

    @Test fun continuousChapterTraversalKeepsWindowBoundedAndProgressAttachedToChapter() = runBlocking {
        val detail = fixture(8)
        val (vm, store) = create(detail)
        try {
            for (chapter in detail.chapters) {
                window(vm, chapter.id)
                report(vm, chapter.id, 0, displayed = true)
                val next = detail.chapters.getOrNull(chapter.sort)
                if (next != null) window(vm, next.id)
                report(vm, chapter.id, 0, displayed = true)
                val current = main { (vm.state.value as ReaderUiState.Content).window }
                assertTrue("window grew to " + current.chapters.size, current.chapters.size <= 3)
                AppGraph.progress.flush()
                assertEquals(chapter.id, AppGraph.progress.resume(detail.id)!!.chapterId)
            }
        } finally { main { store.clear() }; AppGraph.progress.flush() }
    }

    @Test fun lateImageFromPreviousWindowCannotCompleteNewChapter() = runBlocking {
        val detail = fixture(2)
        val (vm, store) = create(detail)
        try {
            val old = window(vm)
            main { vm.openChapterInPlace(detail.chapters[1].id) }
            withTimeout(10_000) { vm.state.filterIsInstance<ReaderUiState.Content>().first { it.window.id != old.id } }
            val chapter = detail.chapters[1].id
            report(vm, chapter, 1, end = true)
            main { vm.imageDisplayed(old.id, old.images.last(), true) }
            AppGraph.progress.flush()
            assertNull(AppGraph.progress.chapter(chapter))
        } finally { main { store.clear() }; AppGraph.progress.flush() }
    }

    @Test fun prepareLocalFixtureForDirectNavigation() = runBlocking {
        val detail = fixture(5)
        assertEquals(5, AppGraph.comics.detail(detail.id).chapters.size)
        assertEquals(2, AppGraph.comics.chapter(detail.id, detail.chapters[0].id).pages.size)
    }
}
