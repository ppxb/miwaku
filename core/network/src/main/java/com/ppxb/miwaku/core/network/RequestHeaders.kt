package com.ppxb.miwaku.core.network

import okhttp3.Headers

/**
 * Single place where request headers are built. The three call sites (API calls, health probes and
 * image downloads) must not disagree about the UA or the signed-header shape.
 */
object RequestHeaders {
    /** Signed API/probe request: token + tokenparam + UA. */
    fun api(ts: Long, version: String, userAgent: String): Headers =
        Headers.Builder()
            .add("token", Codec.token(ts))
            .add("tokenparam", Codec.tokenParam(ts, version))
            .add("user-agent", userAgent)
            .add("accept", "*/*")
            .build()

    /** Public host-config document: unsigned, but still identifies as the app. */
    fun config(userAgent: String): Headers =
        Headers.Builder()
            .add("user-agent", userAgent)
            .build()

    /** Image CDN request: no token, image accept. */
    fun image(userAgent: String): Headers =
        Headers.Builder()
            .add("user-agent", userAgent)
            .add("accept", "image/*")
            .build()
}
