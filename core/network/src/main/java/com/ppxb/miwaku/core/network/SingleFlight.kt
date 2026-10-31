package com.ppxb.miwaku.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Shares one in-flight computation per key.
 *
 * Semantics chosen for API calls: the work runs on the owner's scope, so cancelling a caller only
 * stops that caller from waiting; a failure removes the entry so the next call starts fresh instead
 * of being served a cached error.
 */
internal class SingleFlight<K, V>(
    private val scope: CoroutineScope,
) {
    private val flights = HashMap<K, Deferred<V>>()
    private val lock = Any()

    fun run(key: K, block: suspend () -> V): Deferred<V> =
        synchronized(lock) {
            flights[key]?.takeIf { it.isActive } ?: scope
                .async(start = CoroutineStart.LAZY) { block() }
                .also { flight ->
                    flights[key] = flight
                    flight.invokeOnCompletion {
                        synchronized(lock) {
                            if (flights[key] === flight) flights.remove(key)
                        }
                    }
                    flight.start()
                }
        }
}
