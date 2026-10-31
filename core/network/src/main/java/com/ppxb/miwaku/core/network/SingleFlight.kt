package com.ppxb.miwaku.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Shares one in-flight computation per key.
 *
 * Semantics chosen for API calls: the work runs on the owner's scope, so cancelling one caller does
 * not cancel the request for the others. When the last waiter leaves, the shared work is cancelled,
 * so a request nobody waits for stops instead of occupying the client until its timeout. A failure
 * removes the entry, so the next call starts fresh instead of being served a cached error.
 */
internal class SingleFlight<K, V>(
    private val scope: CoroutineScope,
) {
    private class Entry<V>(val deferred: Deferred<V>) {
        var waiters: Int = 0
    }

    private val flights = HashMap<K, Entry<V>>()
    private val lock = Any()

    suspend fun run(key: K, block: suspend () -> V): V {
        var created: Entry<V>? = null
        val entry =
            synchronized(lock) {
                flights[key]?.takeIf { it.deferred.isActive } ?: Entry(
                    scope.async(start = CoroutineStart.LAZY) { block() },
                ).also {
                    flights[key] = it
                    created = it
                    it.deferred.invokeOnCompletion {
                        synchronized(lock) {
                            if (flights[key] === it) flights.remove(key)
                        }
                    }
                }
            }
        // Started outside the lock: a fast failure must not run its completion handler while the
        // caller still holds the map lock.
        created?.deferred?.start()
        synchronized(lock) { entry.waiters++ }

        try {
            return entry.deferred.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && entry.deferred.isActive) entry.deferred.cancel()
            }
        }
    }
}
