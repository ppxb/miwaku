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
        val snapshot = runBlocking { ApiClient().ensureSetting() }

        Log.i(
            "ApiSmoke",
            "base=${snapshot.baseUrl} version=${snapshot.version} imgHost=${snapshot.imageHost}",
        )
        assertTrue(snapshot.version.isNotEmpty())
        assertTrue(snapshot.imageHost.startsWith("https://"))
    }
}
