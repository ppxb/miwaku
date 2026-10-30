package com.ppxb.miwaku.refactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ppxb.miwaku.core.data.*
import com.ppxb.miwaku.core.data.download.*
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.data.settings.SettingsStore
import com.ppxb.miwaku.core.database.LibraryDatabase
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRecoveryTest {
    @Test fun failureToPersistFailedStatePausesAndCanResume() = runBlocking {
        val detail = ReaderSessionTest().fixture(1)
        val context = TestLibraryContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val library = LibraryStore(context)
        val db = LibraryDatabase.open(context)
        var manager: DownloadManager? = null
        try {
            library.awaitReady(); library.saveDetail(detail)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_task_state BEFORE UPDATE OF state ON download_tasks WHEN NEW.state = 'Failed' BEGIN SELECT RAISE(ABORT, 'controlled failure'); END")
            val failImages = AtomicBoolean(true)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (failImages.get()) throw IOException("controlled image failure")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(testPng().toResponseBody()).build()
            }.build()
            val store = DownloadStore(context, library)
            val downloads = DownloadManager(PageImageStore(context, "test", client), ProgressStore(library),
                AppGraph.comics, store, SettingsStore(context)) { true }
            manager = downloads
            downloads.enqueue(detail.id, detail.title, detail.chapters)
            withTimeout(10_000) { downloads.error.first { it != null }; downloads.isPaused.first { it } }
            assertTrue(downloads.isPaused.value)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_task_state")
            failImages.set(false)
            downloads.resumeAll()
            val completed = withTimeout(10_000) { downloads.tasks.first { it.singleOrNull()?.state == DownloadState.Completed } }
            assertEquals(2, completed.single().donePages)
        } finally { manager?.close(); db.close(); library.close(); context.recycle() }
    }
}
