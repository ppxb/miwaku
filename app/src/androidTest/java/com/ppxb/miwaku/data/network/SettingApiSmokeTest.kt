package com.ppxb.miwaku.data.network

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingApiSmokeTest {
    @Test
    fun fetchSetting() {
        val executor = Executors.newSingleThreadExecutor()
        val snapshot =
            try {
                executor.submit<SettingSnapshot> { ApiClient().fetchSetting().getOrThrow() }
                    .get(40, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

        Log.i(
            "ApiSmoke",
            "base=${snapshot.baseUrl} version=${snapshot.version} imgHost=${snapshot.imageHost}",
        )
        assertTrue(snapshot.version.isNotEmpty())
        assertTrue(snapshot.imageHost.startsWith("https://"))
    }
}
