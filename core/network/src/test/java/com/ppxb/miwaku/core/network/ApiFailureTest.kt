package com.ppxb.miwaku.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFailureTest {
    @Test
    fun wafShapedFailuresCoolDownHarder() {
        assertTrue(ApiFailure.Http(403).isSuspicious)
        assertTrue(ApiFailure.Decode("interstitial page").isSuspicious)
    }

    @Test
    fun transportAndServerFailuresStayTransient() {
        assertFalse(ApiFailure.Network("timeout").isSuspicious)
        assertFalse(ApiFailure.Http(500).isSuspicious)
        assertFalse(ApiFailure.Http(520).isSuspicious)
    }

    @Test
    fun forbiddenFailsOverButUnauthorizedDoesNot() {
        assertTrue(ApiFailure.Http(403).canFailover)
        assertTrue(ApiFailure.Decode("interstitial page").canFailover)
        assertFalse(ApiFailure.Http(401).canFailover)
    }

    @Test
    fun businessErrorsAreNeitherFailoverNorSuspicious() {
        val business = ApiFailure.Business(401, "please log in")

        assertFalse(business.canFailover)
        assertFalse(business.isSuspicious)
    }
}
