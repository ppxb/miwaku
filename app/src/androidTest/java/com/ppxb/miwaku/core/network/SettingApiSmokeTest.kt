package com.ppxb.miwaku.core.network

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingApiSmokeTest {
    @Test
    fun fetchSetting() {
        val endpoints = runBlocking { ApiClient().endpoints() }

        Log.i(
            "ApiSmoke",
            "base=${endpoints.apiBaseUrl} version=${endpoints.version} " +
                "apis=${endpoints.hostPool.size} images=${endpoints.imagePool}",
        )
        assertTrue(endpoints.version.isNotEmpty())
        assertTrue(endpoints.hostPool.isNotEmpty())
        assertTrue(endpoints.imagePool.first().startsWith("https://"))
    }
}
