package com.ppxb.miwaku.core.data.image

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeLogTest {
    private fun entry(key: String, width: Int, height: Int, split: Int) =
        PageSizeLog.Entry(key, width, height, split)

    @Test
    fun recordsRoundTripIncludingTheScrambleCount() {
        val entries =
            listOf(
                entry("1487138/page-1.jpg", 800, 1200, 4),
                entry("1487138/page-2.jpg", 720, 16000, 0),
            )

        val decoded = PageSizeLog.decode(PageSizeLog.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun aTruncatedTailIsDroppedInsteadOfCorruptingEarlierRecords() {
        val full = PageSizeLog.encode(listOf(entry("a/1.jpg", 10, 20, 1), entry("a/2.jpg", 30, 40, 2)))
        val truncated = full.copyOf(full.size - 5)

        val decoded = PageSizeLog.decode(truncated)

        assertEquals(listOf(entry("a/1.jpg", 10, 20, 1)), decoded)
    }

    @Test
    fun tombstonesSurviveTheRoundTrip() {
        val decoded = PageSizeLog.decode(PageSizeLog.encode(listOf(entry("a/1.jpg", 0, 0, 0))))

        assertEquals(listOf(entry("a/1.jpg", 0, 0, 0)), decoded)
    }

    @Test
    fun appendingKeepsEarlierRecords() {
        val file = File.createTempFile("sizes", ".log").apply { deleteOnExit() }

        PageSizeLog.append(file, listOf(entry("a/1.jpg", 10, 20, 1)))
        PageSizeLog.append(file, listOf(entry("a/2.jpg", 30, 40, 2)))

        assertEquals(
            listOf(entry("a/1.jpg", 10, 20, 1), entry("a/2.jpg", 30, 40, 2)),
            PageSizeLog.read(file),
        )
    }

    @Test
    fun garbageDecodesToNothing() {
        assertTrue(PageSizeLog.decode(byteArrayOf(0, 0, 1, 2)).isEmpty())
        assertTrue(PageSizeLog.decode(ByteArray(0)).isEmpty())
    }
}
