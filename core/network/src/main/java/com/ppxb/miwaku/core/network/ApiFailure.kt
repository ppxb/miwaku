package com.ppxb.miwaku.core.network

/**
 * Classified failure of an API call. The failover rules live here so callers never guess whether
 * a retry, a base switch or a page-level error is appropriate.
 */
sealed class ApiFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Connection refused, timeout, TLS reset, DNS failure. */
    class Network(message: String, cause: Throwable? = null) : ApiFailure(message, cause)

    /** HTTP status outside 2xx. [serverMessage] carries the best-effort text from the error body. */
    class Http(val code: Int, val serverMessage: String = "") :
        ApiFailure("HTTP $code${if (serverMessage.isNotBlank()) ": $serverMessage" else ""}")

    /** Envelope was readable but the business `code` is not 200. Never retried or failed over. */
    class Business(val code: Int, val serverMessage: String) : ApiFailure("code $code: $serverMessage")

    /** Body missing, not decryptable or not JSON. */
    class Decode(val detail: String) : ApiFailure(detail)

    /** Whether another API base can plausibly serve the same request. */
    val canFailover: Boolean
        get() =
            when (this) {
                is Network -> true
                is Http -> code in 500..599 || code == 403 || code == 408
                is Decode -> true
                is Business -> false
            }

    /**
     * Whether the failure looks like a WAF/interstitial page rather than a slow link. Such failures
     * cool down harder than a plain timeout, because the host is answering but not with our API.
     */
    val isSuspicious: Boolean
        get() =
            when (this) {
                is Decode -> true
                is Http -> code == 403
                is Network, is Business -> false
            }

    /**
     * Whether an image can be re-requested from the alternate image host. A 404/410 means the
     * page itself is gone and must not rotate the host.
     */
    val canRetryOnAlternateImageHost: Boolean
        get() =
            when (this) {
                is Network -> true
                is Http -> canFailover
                is Decode, is Business -> false
            }
}
