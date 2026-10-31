package com.ppxb.miwaku.core.data.image

import com.ppxb.miwaku.core.network.ApiFailure
import kotlinx.coroutines.CancellationException

/**
 * Whether a failed page fetch may be retried on the alternate image host.
 *
 * Cancellation never rotates the host: the caller is gone, so a second transfer would only waste
 * bandwidth and could pull a different host into the picture for nobody.
 */
internal fun Throwable.canRotateImageHost(): Boolean =
    this !is CancellationException && (this as? ApiFailure)?.canRetryOnAlternateImageHost == true
