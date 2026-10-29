package com.ppxb.miwaku.core.network

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Suspending wrapper around [Call.enqueue] that cancels the underlying call when the coroutine is
 * cancelled. Ported from komikku (Apache-2.0), modified: stack-trace rewriting removed.
 */
suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            runCatching { cancel() }
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }
