package com.ppxb.miwaku.refactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ppxb.miwaku.core.data.LibraryStore
import com.ppxb.miwaku.core.data.ProgressStore
import com.ppxb.miwaku.core.data.download.DownloadStore
import com.ppxb.miwaku.core.database.LibraryDatabase
import com.ppxb.miwaku.model.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryReadingTest {
    private fun context() = TestLibraryContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun detail() = ComicDetail("book", "测试作品", "测试作者", "", emptyList(), "",
        (1..5).map { ChapterSummary("c" + it, "第" + it + "章", it) })

    @Test fun legacyMigrationPreservesKnownPositionWithoutInventingReadStatus() = runBlocking {
        val context = context()
        context.getSharedPreferences("progress", 0).edit().putString("entries", "{\"book\":{\"chapterId\":\"c2\",\"page\":7}}").commit()
        context.getSharedPreferences("history", 0).edit().putString("entries", "[{\"id\":\"book\",\"title\":\"测试作品\"}]").commit()
        val broken = File(context.cacheDir, "reader_details/v1/broken.json").apply { parentFile!!.mkdirs(); writeText("{") }
        var library = LibraryStore(context)
        try {
            withTimeout(10_000) { library.awaitReady() }
            val db = LibraryDatabase.open(context)
            try {
                val p = db.library().progress("c2")!!
                assertEquals(7, p.pageIndex)
                assertNull(p.readAt)
                assertNull(p.lastReadAt)
                assertEquals("book", db.library().chapter("c2")!!.albumId)
                assertNull(db.library().history("book")!!.lastReadAt)
                assertEquals("{", broken.readText())
            } finally { db.close() }
            library.close()
            library = LibraryStore(context)
            withTimeout(10_000) { library.awaitReady() }
            assertEquals(7, ProgressStore(library).resume("book")!!.page)
            assertNotNull(context.getSharedPreferences("progress", 0).getString("entries", null))
        } finally { library.close(); context.recycle() }
    }

    @Test fun downloadedManifestReplacesLongerCachedManifestAndSurvivesReopen() = runBlocking {
        val context = context()
        val cached = File(context.cacheDir, "reader_manifests/v1/c1.json").apply { parentFile!!.mkdirs() }
        fun manifest(count: Int, offline: Boolean) = JSONObject()
            .put(if (offline) "albumId" else "seriesId", "book")
            .put("title", "测试章节").put("imageHost", "https://example.invalid")
            .put("pages", JSONArray().apply { repeat(count) { put(JSONObject().put("name", "$it.png").put("split", 0)) } })
        cached.writeText(manifest(10, false).toString())
        val dir = File(context.filesDir, "downloads/c1").apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest(5, true).toString())
        repeat(5) { index -> File(dir, "pages/" + index.toString().padStart(4, '0') + "_" + index + ".png").apply {
            parentFile!!.mkdirs(); writeBytes(testPng())
        } }
        val library = LibraryStore(context)
        try {
            library.awaitReady()
            val db = LibraryDatabase.open(context)
            try {
                assertEquals(5, db.library().pages("c1").size)
                assertEquals(5, db.library().downloadedPages("c1").size)
                assertEquals("Completed", db.library().task("c1")!!.state)
            } finally { db.close() }
            assertEquals(5, library.chapter("c1", null)!!.pages.size)
            assertEquals("c1", library.detail("book")!!.first.chapters.single().id)
        } finally { library.close(); context.recycle() }
    }

    @Test fun incrementalProgressUnreadSelectionAndManualOverridesAreConsistent() = runBlocking {
        val context = context()
        val library = LibraryStore(context)
        try {
            library.awaitReady()
            library.saveDetail(detail())
            val progress = ProgressStore(library)
            val session = progress.beginSession()
            progress.record(session, "book", ReadingProgress("c1", 9, "9.png"), completed = true)
            progress.markRead("c4", true)
            progress.record(session, "book", ReadingProgress("c3", 2, "2.png", .25f))
            progress.flush()
            val downloads = DownloadStore(context, library)
            downloads.enqueue("book", "测试作品", null, unreadOnly = true)
            downloads.enqueue("book", "测试作品", null, unreadOnly = true)
            val db = LibraryDatabase.open(context)
            try {
                assertEquals(setOf("c2", "c3", "c5"), db.library().tasks().map { it.chapterId }.toSet())
                assertEquals(3, db.library().tasks().size)
                assertEquals("c3", db.library().history("book")!!.chapterId)
                assertEquals(.25f, db.library().progress("c3")!!.offsetFraction, .001f)
            } finally { db.close() }
            progress.markRead("c1", false)
            progress.record(session, "book", ReadingProgress("c1", 3, "3.png"))
            progress.flush()
            assertNull(progress.chapter("c1")!!.readAt)
            progress.record(session, "book", ReadingProgress("c3", 9, "9.png"), completed = true)
            progress.flush()
            assertEquals(setOf("c2", "c3", "c5"), downloads.tasks.first { it.size == 3 }.map { it.chapterId }.toSet())
        } finally { library.close(); context.recycle() }
    }

    @Test fun oldSessionCannotOverwriteNewSessionAndContinuousScrollIsPeriodicallySaved() = runBlocking {
        val context = context()
        val library = LibraryStore(context)
        try {
            library.awaitReady(); library.saveDetail(detail())
            val progress = ProgressStore(library)
            val old = progress.beginSession()
            val fresh = progress.beginSession()
            progress.record(old, "book", ReadingProgress("c1", 99))
            repeat(14) { index -> progress.record(fresh, "book", ReadingProgress("c2", index)); delay(100) }
            val saved = progress.chapter("c2")
            assertNotNull(saved)
            assertTrue(saved!!.page >= 8)
            assertNull(progress.chapter("c1"))
            progress.flushSession(old, end = true)
            progress.record(fresh, "book", ReadingProgress("c2", 20))
            progress.flush()
            assertEquals(20, progress.resume("book")!!.page)
        } finally { library.close(); context.recycle() }
    }

    @Test fun writeFailureRetainsCompletionAndAcceptsNewSession() = runBlocking {
        val context = context()
        val library = LibraryStore(context)
        val db = LibraryDatabase.open(context)
        try {
            library.awaitReady(); library.saveDetail(detail())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_progress BEFORE INSERT ON chapter_progress BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            val progress = ProgressStore(library)
            val old = progress.beginSession()
            progress.record(old, "book", ReadingProgress("c1", 9), completed = true)
            withTimeout(5_000) { progress.error.first { it != null } }
            progress.record(old, "book", ReadingProgress("c1", 2))
            val fresh = progress.beginSession()
            progress.record(fresh, "book", ReadingProgress("c2", 3))
            assertTrue(runCatching { progress.flush() }.isFailure)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_progress")
            progress.flush()
            assertNotNull(progress.chapter("c1")!!.readAt)
            assertEquals(2, progress.chapter("c1")!!.page)
            assertEquals(3, progress.chapter("c2")!!.page)
            assertEquals("c2", progress.resume("book")!!.chapterId)
        } finally { db.close(); library.close(); context.recycle() }
    }

    @Test fun malformedManifestCannotPublishATruncatedChapter() = runBlocking {
        val context = context()
        val file = File(context.cacheDir, "reader_manifests/v1/c1.json").apply { parentFile!!.mkdirs() }
        file.writeText("{\"seriesId\":\"book\",\"pages\":[{\"name\":\"0.png\"},{}]}")
        context.getSharedPreferences("progress", 0).edit().putString("entries", "{\"book\":{\"chapterId\":\"c1\",\"page\":0}}").commit()
        val library = LibraryStore(context)
        try {
            library.awaitReady()
            val db = LibraryDatabase.open(context)
            try {
                assertFalse(db.library().chapter("c1")!!.hasManifest)
                assertTrue(db.library().pages("c1").isEmpty())
                assertNull(db.library().progress("c1")!!.readAt)
            } finally { db.close() }
        } finally { library.close(); context.recycle() }
    }

    @Test fun taskStreamStartsWithRestoredSnapshotInsteadOfAnEmptyPlaceholder() = runBlocking {
        val context = context()
        File(context.filesDir, "downloads/tasks.json").apply {
            parentFile!!.mkdirs()
            writeText("[{\"albumId\":\"book\",\"chapterId\":\"c1\",\"albumTitle\":\"测试\",\"chapterName\":\"第一章\",\"state\":\"Running\"}]")
        }
        val library = LibraryStore(context)
        try {
            val store = DownloadStore(context, library)
            val first = withTimeout(10_000) { store.tasks.first() }
            assertEquals(1, first.size)
            assertEquals("c1", first.single().chapterId)
            assertEquals(com.ppxb.miwaku.core.data.download.DownloadState.Queued, first.single().state)
        } finally { library.close(); context.recycle() }
    }
}
