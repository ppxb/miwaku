package com.ppxb.miwaku.core.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The persisted document carries probe records (schema v3) and still reads v2 documents. */
@RunWith(AndroidJUnit4::class)
class EndpointStateStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "endpoint_state_test"
    private val prefs by lazy { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    @After
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun probeRecordsSurviveARoundTrip() {
        val store = SharedEndpointStateStore(context, prefsName)
        val state =
            EndpointState(
                probedAt = 1L,
                apiVersion = "2.1.12",
                api = listOf(EndpointHealth("https://a.net", latencyMs = 10)),
                image = listOf(EndpointHealth("https://img.net", latencyMs = 20)),
                chosenApi = "https://a.net",
                chosenImage = "https://img.net",
                apiProbes =
                    listOf(
                        ProbeRecord("https://a.net", EndpointStatus.Ok, 10, 200),
                        ProbeRecord("https://b.net", EndpointStatus.Dead, 6_000, null, "timeout"),
                    ),
                imageProbes =
                    listOf(ProbeRecord("https://img.net", EndpointStatus.Degraded, 30, 404, "HTTP 404")),
            )

        store.write(state)
        val read = checkNotNull(store.read())

        assertEquals(EndpointState.SCHEMA_VERSION, read.version)
        assertEquals(state.apiProbes, read.apiProbes)
        assertEquals(state.imageProbes, read.imageProbes)
    }

    @Test
    fun aVersion2DocumentReadsWithEmptyProbeRecords() {
        prefs
            .edit()
            .putString(
                "state",
                """{"version":2,"probedAt":5,"apiVersion":"2.1.12",""" +
                    """"api":[{"url":"https://old.net","failures":1,"cooldownUntil":9,"latencyMs":7,"lastSuccessAt":3}],""" +
                    """"image":[],"chosenApi":"https://old.net","chosenImage":"https://img.net"}""",
            )
            .commit()

        val read = checkNotNull(SharedEndpointStateStore(context, prefsName).read())

        assertEquals(EndpointState.SCHEMA_VERSION, read.version)
        assertTrue(read.apiProbes.isEmpty())
        assertTrue(read.imageProbes.isEmpty())
        assertEquals("https://old.net", read.api.single().url)
        assertEquals(1, read.api.single().failures)
    }
}
