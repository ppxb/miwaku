package com.ppxb.miwaku.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFailoverTest {
    private class MemoryStateStore(private var state: EndpointState) : EndpointStateStore {
        override fun read(): EndpointState = state

        override fun write(state: EndpointState) {
            this.state = state
        }
    }

    @Test
    fun imageFailoverReturnsImmediatelyAndRefreshesInTheBackground() = runBlocking {
        val calls = AtomicInteger()
        val blockingClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls.incrementAndGet()
                    Thread.sleep(3_000)
                    throw IOException("blocked")
                }
                .build()
        val store =
            MemoryStateStore(
                EndpointState(
                    probedAt = System.currentTimeMillis(),
                    apiVersion = "2.1.12",
                    api = listOf(EndpointHealth("https://a.example")),
                    image = listOf(EndpointHealth("https://img-a.example")),
                    chosenApi = "https://a.example",
                    chosenImage = "https://img-a.example",
                ),
            )
        val client = ApiClient(blockingClient, store, blockingClient)

        val started = System.nanoTime()
        val next = client.nextImageHost("https://img-a.example")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNotNull(next)
        assertTrue("must not hand back the failed host", next != "https://img-a.example")
        assertTrue("image failover must not wait for probes, took ${elapsedMs}ms", elapsedMs < 1_000L)

        delay(300)
        assertTrue("the pool refresh must still be scheduled in the background", calls.get() > 0)
    }
}
