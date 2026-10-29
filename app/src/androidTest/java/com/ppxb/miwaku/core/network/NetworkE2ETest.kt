package com.ppxb.miwaku.core.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.image.PageImage
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network smoke tests. They run against the live API, so they need an emulator or device
 * with working connectivity; they are not part of the unit test suite.
 */
@RunWith(AndroidJUnit4::class)
class NetworkE2ETest {

    @Before
    fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun latestAlbumChapterAndFirstPage() = runBlocking {
        val pages = firstChapterPages(minPages = 1)
        val file = withTimeout(IMAGE_TIMEOUT_MS) { AppGraph.pageStore.prepare(pages.first()) }
        assertTrue("image file missing", file.isFile)
        assertTrue("image file empty", file.length() > 1024)
    }

    @Test
    fun cancelledDownloadsReleaseSlots() = runBlocking {
        val pages = firstChapterPages(minPages = 4)
        pages.take(4).forEach { AppGraph.pageStore.invalidate(it) }

        val jobs =
            pages.take(3).map { page ->
                launch(Dispatchers.IO) {
                    runCatching { AppGraph.pageStore.prepare(page) }
                }
            }
        delay(200)
        val stillRunning = jobs.count { it.isActive }
        assertTrue("downloads finished before cancellation, test inconclusive", stillRunning > 0)
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }

        val elapsed = measureTimeMillis {
            withTimeout(IMAGE_TIMEOUT_MS) { AppGraph.pageStore.prepare(pages[3]) }
        }
        assertTrue("download slot was not released, waited ${elapsed}ms", elapsed < IMAGE_TIMEOUT_MS)
    }

    /** Walks latest -> album -> chapter until a chapter with [minPages] images is found. */
    private suspend fun firstChapterPages(minPages: Int): List<PageImage> {
        val comics = AppGraph.comics.latest(0)
        assertTrue("latest returned no comics", comics.isNotEmpty())
        for (summary in comics.take(3)) {
            val detail =
                runCatching { AppGraph.comics.detail(summary.id) }.getOrNull() ?: continue
            for (chapter in detail.chapters.take(3)) {
                val content =
                    runCatching { AppGraph.comics.chapter(detail.id, chapter.id) }.getOrNull()
                        ?: continue
                if (content.pages.size >= minPages) {
                    return content.pages.mapIndexed { index, page ->
                        PageImage(
                            chapterId = content.chapterId,
                            fileName = page.fileName,
                            index = index,
                            splitCount = page.splitCount,
                            url = page.url,
                        )
                    }
                }
            }
        }
        throw AssertionError("no album/chapter with at least $minPages pages")
    }

    private companion object {
        const val IMAGE_TIMEOUT_MS = 45_000L
    }
}
