package com.ppxb.miwaku.core.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun concurrentCallsWithTheSameKeyRunOnce() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val runs = AtomicInteger()

        val results =
            (1..5).map {
                async {
                    flights.run("key") {
                        runs.incrementAndGet()
                        delay(100)
                        42
                    }
                }
            }.awaitAll()

        assertEquals(1, runs.get())
        assertTrue(results.all { it == 42 })
    }

    @Test
    fun differentKeysRunSeparately() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val runs = AtomicInteger()

        val values =
            listOf("a", "b").map { key ->
                async {
                    flights.run(key) {
                        runs.incrementAndGet()
                        delay(50)
                        key.length
                    }
                }
            }.awaitAll()

        assertEquals(2, runs.get())
        assertEquals(listOf(1, 1), values)
    }

    @Test
    fun aFailureReachesEveryWaiterAndTheNextCallRetries() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val runs = AtomicInteger()
        val failing: suspend () -> Int = {
            runs.incrementAndGet()
            delay(50)
            throw IllegalStateException("boom")
        }

        val attempts =
            (1..3).map {
                async { runCatching { flights.run("key", failing) } }
            }.awaitAll()

        assertTrue(attempts.all { it.exceptionOrNull() is IllegalStateException })
        assertEquals("all waiters share one attempt", 1, runs.get())

        runCatching { flights.run("key", failing) }
        assertEquals("a failed attempt must not be cached", 2, runs.get())
    }

    @Test
    fun aCompletedFlightDoesNotServeLaterCalls() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val runs = AtomicInteger()

        flights.run("key") { runs.incrementAndGet() }
        flights.run("key") { runs.incrementAndGet() }

        assertEquals(2, runs.get())
    }

    @Test
    fun theLastWaiterLeavingCancelsTheSharedWork() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val cancelled = CompletableDeferred<Unit>()

        val waiter =
            launch {
                runCatching {
                    flights.run("key") {
                        try {
                            delay(5_000)
                        } catch (cancel: CancellationException) {
                            cancelled.complete(Unit)
                            throw cancel
                        }
                        1
                    }
                }
            }
        delay(200)
        waiter.cancelAndJoin()

        withTimeout(2_000) { cancelled.await() }
    }

    @Test
    fun aRemainingWaiterKeepsTheWorkAlive() = runBlocking {
        val flights = SingleFlight<String, Int>(scope)
        val runs = AtomicInteger()

        val first = async {
            flights.run("key") {
                runs.incrementAndGet()
                delay(400)
                7
            }
        }
        delay(100)
        val second = async { flights.run("key") { runs.incrementAndGet(); 9 } }
        delay(100)
        first.cancelAndJoin()

        assertEquals(7, second.await())
        assertEquals("one request served both waiters", 1, runs.get())
    }
}
