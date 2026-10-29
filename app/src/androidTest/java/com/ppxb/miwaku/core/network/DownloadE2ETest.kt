package com.ppxb.miwaku.core.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.download.DownloadTask
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.model.ComicDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-network download + offline-read check; needs a device with connectivity. */
@RunWith(AndroidJUnit4::class)
class DownloadE2ETest {

    @Before
    fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun downloadsChapterAndReadsItFromDownloads() = runBlocking {
        val detail = firstDetail()
        val chapter = detail.chapters.last()

        // A queue paused by a previous session must not make the test hang.
        AppGraph.downloadManager.resumeAll()
        AppGraph.downloadManager.enqueue(detail.id, detail.title, listOf(chapter))

        val completed =
            withTimeout(DOWNLOAD_TIMEOUT_MS) {
                AppGraph.downloadManager.tasks.first { tasks ->
                    tasks.any { it.chapterId == chapter.id && it.state == DownloadState.Completed }
                }
            }
        val task = completed.first { it.chapterId == chapter.id }
        assertTrue("task did not complete", task.state == DownloadState.Completed)
        assertTrue("no pages recorded", task.totalPages > 0)

        val manifest = AppGraph.downloadStore.manifest(chapter.id)
        assertTrue("manifest missing", manifest != null && manifest.pages.isNotEmpty())

        val content = AppGraph.comics.chapter(detail.id, chapter.id)
        val page = content.pages.first()
        val image =
            PageImage(
                chapterId = chapter.id,
                fileName = page.fileName,
                index = 0,
                splitCount = page.splitCount,
                url = page.url,
            )
        val file = withTimeout(30_000) { AppGraph.pageStore.prepare(image) }
        assertTrue("downloaded file missing", file.isFile && file.length() > 0)
        assertTrue("reader did not resolve the downloaded copy: ${file.absolutePath}", file.absolutePath.contains("/downloads/"))
    }

    private suspend fun firstDetail(): ComicDetail {
        val comics = AppGraph.comics.latest(0)
        assertTrue("latest returned no comics", comics.isNotEmpty())
        for (summary in comics.take(3)) {
            val detail = runCatching { AppGraph.comics.detail(summary.id) }.getOrNull() ?: continue
            if (detail.chapters.isNotEmpty()) return detail
        }
        throw AssertionError("no album with chapters")
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
