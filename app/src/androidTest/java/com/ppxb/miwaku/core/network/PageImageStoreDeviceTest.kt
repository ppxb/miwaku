package com.ppxb.miwaku.core.network

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device checks for the on-disk page pipeline: direct write into the offline slot, the persisted
 * size snapshot, and rejection of a body that was cut short.
 */
@RunWith(AndroidJUnit4::class)
class PageImageStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheRoot = File(context.cacheDir, "reader_pages/v1")

    private val onePixelPng: ByteArray =
        Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        )

    @Test
    fun downloadWritesTheOfflineSlotDirectly() = runBlocking {
        cacheRoot.deleteRecursively()
        val store = store(onePixelPng)
        val image = image("direct")
        val target = File(context.cacheDir, "offline_test/${image.fileName}")

        val produced = store.prepareTo(image, target)

        assertEquals(target.absolutePath, produced.absolutePath)
        assertTrue(target.isFile && target.length() > 0L)
        assertEquals(target.absolutePath, store.cachedFile(image)?.absolutePath)
        assertFalse("no disposable reader copy should be left", cachedCopyOf(image).isFile)
        assertNotNull(store.sizeOf(image))
    }

    @Test
    fun sizeSnapshotSurvivesAFreshStore() = runBlocking {
        cacheRoot.deleteRecursively()
        val store = store(onePixelPng)
        val image = image("sizes")
        store.prepare(image)

        // The log is appended debounced; give it a moment, then restore from a new instance.
        val snapshot = File(cacheRoot, "sizes.log")
        withTimeoutOrNull(5_000L) {
            while (!snapshot.isFile || snapshot.length() == 0L) delay(100)
        }
        assertTrue("size log must exist", snapshot.isFile && snapshot.length() > 0L)

        val restored = store(onePixelPng)
        val recovered =
            withTimeoutOrNull(5_000L) {
                while (restored.sizeOf(image) == null) delay(50)
                restored.sizeOf(image)
            }
        assertNotNull("sizes must be restored without re-reading the page", recovered)
        assertEquals(1, recovered!!.width)
        assertEquals(1, recovered.height)
    }

    @Test
    fun aTruncatedBodyIsRejectedBeforeCommit() = runBlocking {
        cacheRoot.deleteRecursively()
        val truncated = onePixelPng.copyOf(onePixelPng.size - 6)
        val store = store(truncated)
        val image = image("truncated")

        val failure = runCatching { store.prepare(image) }.exceptionOrNull()

        assertTrue("expected a retryable network failure, got $failure", failure is ApiFailure.Network)
        assertFalse("nothing may be committed", cachedCopyOf(image).isFile)
    }

    @Test
    fun changingTheScrambleCountInvalidatesTheCachedBytes() = runBlocking {
        cacheRoot.deleteRecursively()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val store = store(onePixelPng, requests)
        val first = image("scramble", splitCount = 1)
        store.prepare(first)
        assertEquals(1, requests.get())
        assertEquals(1, store.sizeOf(first)?.splitCount)

        val second = image("scramble", splitCount = 2)
        store.prepare(second)

        assertEquals("bytes must be fetched again for the new scramble", 2, requests.get())
        assertEquals(2, store.sizeOf(second)?.splitCount)
    }

    private fun store(bytes: ByteArray, requests: java.util.concurrent.atomic.AtomicInteger? = null): PageImageStore =
        PageImageStore(
            context = context,
            userAgent = "test-agent",
            httpClient =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        requests?.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(bytes.toResponseBody("image/png".toMediaType()))
                            .build()
                    }
                    .build(),
        )

    private fun image(seed: String, splitCount: Int = 1) =
        PageImage(
            chapterId = "device-$seed",
            fileName = "page-$seed.png",
            index = 0,
            splitCount = splitCount,
            url = "https://img.example/page-$seed.png",
        )

    private fun cachedCopyOf(image: PageImage): File {
        val chapter = File(cacheRoot, image.chapterId)
        return chapter.listFiles()?.firstOrNull { it.name.startsWith("0000_") } ?: File(chapter, "absent")
    }
}
