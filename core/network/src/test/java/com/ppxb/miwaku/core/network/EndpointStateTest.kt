package com.ppxb.miwaku.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointStateTest {
    private val now = 1_000_000L

    @Test
    fun coolingDownEntriesRankBehindHealthyOnes() {
        val state =
            EndpointState(
                api =
                    listOf(
                        EndpointHealth("https://cold", cooldownUntil = now + 60_000),
                        EndpointHealth("https://warm", failures = 0, latencyMs = 400),
                        EndpointHealth("https://flaky", failures = 2, latencyMs = 10),
                    ),
                image = listOf(EndpointHealth("https://img-cold", cooldownUntil = now + 60_000)),
            )

        assertEquals(listOf("https://warm", "https://flaky", "https://cold"), state.rankedApi(now).map { it.url })
        assertEquals(listOf("https://img-cold"), state.rankedImage(now).map { it.url })
    }

    @Test
    fun snapshotRequiresAFullSelection() {
        val complete =
            EndpointState(
                apiVersion = "2.1.12",
                chosenApi = "https://api/",
                chosenImage = "https://img/",
            )
        val snapshot = complete.toSnapshot()

        assertEquals("https://api", snapshot?.baseUrl)
        assertEquals("https://img", snapshot?.imageHost)
        assertEquals("2.1.12", snapshot?.version)

        assertNull(complete.copy(chosenImage = null).toSnapshot())
        assertNull(complete.copy(apiVersion = "").toSnapshot())
        assertNull(EndpointState().toSnapshot())
    }
}
