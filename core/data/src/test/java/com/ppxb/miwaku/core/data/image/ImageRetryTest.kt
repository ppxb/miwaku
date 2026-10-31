package com.ppxb.miwaku.core.data.image

import com.ppxb.miwaku.core.network.ApiFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRetryTest {
    @Test
    fun cancellationNeverRotatesTheHost() {
        assertFalse(CancellationException("page released").canRotateImageHost())
    }

    @Test
    fun transportFailuresRotate() {
        assertTrue(ApiFailure.Network("timeout").canRotateImageHost())
        assertTrue(ApiFailure.Http(403).canRotateImageHost())
        assertTrue(ApiFailure.Http(520).canRotateImageHost())
    }

    @Test
    fun decodeAndBusinessFailuresStayOnTheSameHost() {
        assertFalse(ApiFailure.Decode("interstitial").canRotateImageHost())
        assertFalse(ApiFailure.Business(401, "please log in").canRotateImageHost())
        assertFalse(ApiFailure.Http(404).canRotateImageHost())
        assertFalse(IOException("not an ApiFailure").canRotateImageHost())
    }
}
