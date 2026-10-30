package com.ppxb.miwaku.refactor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import com.ppxb.miwaku.core.data.image.*
import com.ppxb.miwaku.ui.screen.reader.PageDemand
import com.ppxb.miwaku.ui.screen.reader.ReaderPrefetcher
import com.ppxb.miwaku.ui.screen.reader.ScrambleDecoder
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {
    private fun context() = TestLibraryContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun store(context: TestLibraryContext) = PageImageStore(context, "reader-test",
        OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).readTimeout(30, TimeUnit.SECONDS).build())
    private fun image(server: ControlledImages, name: String) = PageImage("chapter", name + ".png", name.hashCode(), 0, server.url(name))

    @Test fun cancellingBodyReadsReleasesAllSlotsWithoutWaitingForTimeout() = runBlocking {
        val context = context()
        val server = ControlledImages(3)
        try {
            val store = store(context)
            val jobs = (1..3).map { async { store.prepare(image(server, "blocked" + it)) } }
            server.awaitHeaders()
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            val start = System.nanoTime()
            val file = withTimeout(5_000) { store.prepare(image(server, "visible"), PagePriority.Visible) }
            assertTrue(file.length() > 0)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
            assertEquals(4, server.paths.size)
        } finally { server.close(); context.recycle() }
    }

    @Test fun oneCancelledWaiterDoesNotCancelAnotherOwner() = runBlocking {
        val context = context()
        val server = ControlledImages(1)
        try {
            val store = store(context)
            val image = image(server, "shared")
            val first = async { store.prepare(image) }
            server.awaitHeaders()
            val registered = CompletableDeferred<Unit>()
            val second = async { store.prepare(image, flow {
                emit(PagePriority.Visible); registered.complete(Unit); awaitCancellation()
            }) }
            registered.await()
            first.cancelAndJoin()
            assertTrue(second.isActive)
            assertEquals(1, server.paths.size)
            server.release(0)
            assertTrue(withTimeout(5_000) { second.await() }.isFile)
            assertEquals(1, server.paths.size)
        } finally { server.close(); context.recycle() }
    }

    @Test fun pendingRequestCanBePromotedAheadOfEarlierNearbyWork() = runBlocking<Unit> {
        val context = context()
        val server = ControlledImages(3)
        try {
            val store = store(context)
            val held = (1..3).map { async { store.prepare(image(server, "held" + it)) } }
            server.awaitHeaders()
            val registered = CompletableDeferred<Unit>()
            val promote = CompletableDeferred<Unit>()
            val promoted = CompletableDeferred<Unit>()
            val target = async { store.prepare(image(server, "target"), flow {
                emit(PagePriority.Warmup); registered.complete(Unit)
                promote.await(); emit(PagePriority.Visible); promoted.complete(Unit); awaitCancellation()
            }) }
            registered.await()
            val otherRegistered = CompletableDeferred<Unit>()
            val other = async { store.prepare(image(server, "nearby"), flow {
                emit(PagePriority.Nearby); otherRegistered.complete(Unit); awaitCancellation()
            }) }
            otherRegistered.await(); promote.complete(Unit); promoted.await()
            server.release(0)
            withTimeout(5_000) { target.await(); other.await() }
            assertEquals("/target", server.paths[3])
            server.releaseAll(); held.awaitAll()
        } finally { server.close(); context.recycle() }
    }

    @Test fun overlappingPrefetchWindowsKeepTheirOriginalRequests() = runBlocking {
        val context = context()
        val server = ControlledImages(3)
        try {
            val store = store(context)
            val ready = AtomicInteger()
            val prefetcher = ReaderPrefetcher(this, store, { ready.incrementAndGet() }, { fail("unexpected page failure") })
            val pages = (1..3).map { image(server, "page" + it) }
            prefetcher.update(pages.map { PageDemand(it, PagePriority.Nearby) })
            server.awaitHeaders()
            prefetcher.update(pages.mapIndexed { index, image -> PageDemand(image, if (index == 1) PagePriority.Visible else PagePriority.Nearby) })
            server.releaseAll()
            withTimeout(5_000) { while (ready.get() != 3) delay(20) }
            assertEquals(3, server.paths.size)
            prefetcher.cancel()
        } finally { server.close(); context.recycle() }
    }

    @Test fun stripDecoderPreservesOriginalRemainderBoundaries() = runBlocking {
        val context = context()
        try {
            val bitmap = Bitmap.createBitmap(4, 10, Bitmap.Config.ARGB_8888)
            repeat(10) { y -> repeat(4) { x -> bitmap.setPixel(x, y, Color.rgb(y * 20, 0, 0)) } }
            val file = File(context.cacheDir, "strips.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            for (budget in listOf(0L, 1024L)) {
                val decoded = (ScrambleDecoder(file, 3, 4, budget).decode().image as BitmapImage).bitmap
                try {
                    val expectedRows = listOf(6, 7, 8, 9, 3, 4, 5, 0, 1, 2)
                    expectedRows.forEachIndexed { y, original -> assertEquals(Color.rgb(original * 20, 0, 0), decoded.getPixel(2, y)) }
                } finally { decoded.recycle() }
            }
        } finally { context.recycle() }
    }

    @Test fun longStripProducesBoundedOutput() = runBlocking {
        val context = context()
        try {
            val file = File(context.cacheDir, "long.png").apply { writeBytes(testPng(512, 16_000)) }
            val result = (ScrambleDecoder(file, 10, 1080).decode().image as BitmapImage).bitmap
            try {
                assertTrue(result.height <= 4096)
                assertTrue(result.width <= 1080)
                assertEquals(255, Color.alpha(result.getPixel(result.width / 2, result.height - 1)))
            } finally { result.recycle() }
        } finally { context.recycle() }
    }

    @Test fun overBudgetPngUsesSmallRegions() = runBlocking {
        val context = context()
        try {
            val file = File(context.cacheDir, "very-long.png")
            writeSolidPng(file, 512, 80_000)
            val decoder = ScrambleDecoder(file, 10, 1080)
            val result = (decoder.decode().image as BitmapImage).bitmap
            try {
                assertEquals("regions", decoder.strategy)
                assertTrue(decoder.peakBitmapBytes < 2L * 1024 * 1024)
                assertEquals(Color.rgb(45, 120, 210), result.getPixel(result.width / 2, result.height - 1))
            } finally { result.recycle() }
        } finally { context.recycle() }
    }

    @Test fun repairedCacheIsPreferredOverBrokenOfflineCopy() = runBlocking {
        val context = context()
        val server = ControlledImages(0)
        try {
            val broken = File(context.filesDir, "broken.png").apply { writeText("invalid image") }
            val store = PageImageStore(context, "test", OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(),
                localFile = { broken })
            val image = image(server, "repair")
            val repaired = withTimeout(5_000) { store.prepare(image) }
            assertNotEquals(broken, repaired)
            assertEquals(repaired, store.cachedFile(image))
        } finally { server.close(); context.recycle() }
    }
}

private class ControlledImages(private val blocked: Int) : Closeable {
    private val body = testPng()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val gates = List(blocked) { CountDownLatch(1) }
    private val headers = CountDownLatch(blocked)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val paths = CopyOnWriteArrayList<String>()
    private val count = AtomicInteger()

    init {
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                executor.submit {
                    try {
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: return@submit
                        val path = line.split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val index = count.getAndIncrement()
                        paths += path
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        output.flush()
                        if (index < blocked) { headers.countDown(); gates[index].await() }
                        output.write(body); output.flush()
                    } catch (_: Exception) { /* Expected when a client cancels its blocked body. */ }
                    finally { socket.close() }
                }
            }
        }
    }
    fun url(path: String) = "http://127.0.0.1:" + server.localPort + "/" + path
    suspend fun awaitHeaders() = withContext(Dispatchers.IO) { check(headers.await(5, TimeUnit.SECONDS)) }
    fun release(index: Int) = gates[index].countDown()
    fun releaseAll() = gates.forEach { it.countDown() }
    override fun close() { server.close(); releaseAll(); sockets.forEach { it.close() }; executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
}
