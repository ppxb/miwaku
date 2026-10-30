package com.ppxb.miwaku.refactor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.settings.ReadingMode
import com.ppxb.miwaku.ui.screen.reader.ReaderScreen
import com.ppxb.miwaku.ui.screen.reader.ReaderViewModel
import com.ppxb.miwaku.ui.theme.MiwakuTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitChapterSwitchStartsAtItsOwnOffset() {
        val detail = runBlocking { ReaderSessionTest().fixture(3) }
        val holder = ViewModelStore()
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread {
            vm = ReaderViewModel(SavedStateHandle(mapOf("albumId" to detail.id, "chapterId" to detail.chapters[0].id, "page" to 0)))
            holder.put("reader", vm)
        }
        try {
            compose.setContent { MiwakuTheme { ReaderScreen(onBack = {}, viewModel = vm) } }
            compose.waitUntil(10_000) { runBlocking { AppGraph.progress.chapter(detail.chapters[0].id) != null } }
            compose.onNodeWithTag("reader-pages").performTouchInput {
                swipe(Offset(width / 2f, height * .8f), Offset(width / 2f, height * .4f), 500)
            }
            compose.waitUntil(10_000) { runBlocking { (AppGraph.progress.chapter(detail.chapters[0].id)?.offsetFraction ?: 0f) > .05f } }
            compose.waitForIdle()
            runBlocking { AppGraph.progress.flush() }
            val before = runBlocking { AppGraph.progress.chapter(detail.chapters[0].id)!! }
            compose.runOnIdle { vm.openChapterInPlace(detail.chapters[1].id) }
            compose.waitUntil(10_000) { runBlocking { AppGraph.progress.chapter(detail.chapters[1].id) != null } }
            val saved = runBlocking { AppGraph.progress.chapter(detail.chapters[1].id)!! }
            assertEquals(0, saved.page)
            assertEquals(0f, saved.offsetFraction, .01f)
            val after = runBlocking { AppGraph.progress.chapter(detail.chapters[0].id)!! }
            assertEquals(before.page, after.page)
            assertEquals(before.offsetFraction, after.offsetFraction, .01f)
        } catch (failure: Throwable) {
            val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val report = org.json.JSONObject().put("state", vm.state.value.toString()).put("failure", failure.stackTraceToString())
            compose.runOnUiThread {
                for (name in listOf("viewport", "shown", "jumping", "currentGlobal", "session", "lastPosition")) {
                    val field = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    report.put(name, field.get(vm)?.toString() ?: "null")
                }
            }
            java.io.File(base.getExternalFilesDir(null), "reader-ui-failure.json").writeText(report.toString(2))
            runCatching {
                val screenshot = compose.onNodeWithTag("reader-pages").captureToImage().asAndroidBitmap()
                java.io.File(base.getExternalFilesDir(null), "reader-ui-failure.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw failure
        } finally { compose.runOnUiThread { holder.clear() }; runBlocking { AppGraph.progress.flush() } }
    }

    @Test fun settledLastPagerPageMarksReadAfterImageDisplay() {
        val detail = runBlocking { ReaderSessionTest().fixture(2) }
        AppGraph.settings.setDefaultReadingMode(ReadingMode.PagedLeftToRight)
        val holder = ViewModelStore()
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread {
            vm = ReaderViewModel(SavedStateHandle(mapOf("albumId" to detail.id, "chapterId" to detail.chapters[0].id, "page" to 0)))
            holder.put("reader", vm)
        }
        try {
            compose.setContent { MiwakuTheme { ReaderScreen(onBack = {}, viewModel = vm) } }
            compose.waitUntil(10_000) { runBlocking { AppGraph.progress.chapter(detail.chapters[0].id) != null } }
            assertNull(runBlocking { AppGraph.progress.chapter(detail.chapters[0].id)!!.readAt })
            compose.onNodeWithTag("reader-pager").performTouchInput { click(Offset(width * .9f, height * .5f)) }
            compose.waitUntil(10_000) { runBlocking { AppGraph.progress.chapter(detail.chapters[0].id)?.readAt != null } }
        } finally {
            compose.runOnUiThread { holder.clear() }
            runBlocking { AppGraph.progress.flush() }
            AppGraph.settings.setDefaultReadingMode(ReadingMode.Webtoon)
        }
    }
}
