package com.ppxb.miwaku.core.data.image

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageIntegrityTest {
    private fun file(bytes: IntArray): File =
        File.createTempFile("integrity", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(bytes.size) { bytes[it].toByte() })
        }

    private fun jpeg(withEnd: Boolean): File {
        val head = listOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46)
        val tail = if (withEnd) listOf(0xFF, 0xD9) else listOf(0x00, 0x00)
        return file((head + List(32) { 0x11 } + tail).toIntArray())
    }

    private fun png(withIend: Boolean): File {
        val head = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val body = List(24) { 0x22 }
        val end = if (withIend) listOf(0, 0, 0, 0) + "IEND".map { it.code } + listOf(0, 0, 0, 0) else listOf()
        return file((head + body + end).toIntArray())
    }

    private fun gif(withTrailer: Boolean): File {
        val head = "GIF89a".map { it.code }
        val body = List(16) { 0x33 }
        val end = if (withTrailer) listOf(0x3B) else listOf(0x00)
        return file((head + body + end).toIntArray())
    }

    private fun webp(declaredSize: Int): File {
        val head = "RIFF".map { it.code } + le(declaredSize) + "WEBP".map { it.code }
        val payload = List(16) { 0x44 }
        return file((head + payload).toIntArray())
    }

    private fun le(value: Int): List<Int> =
        listOf(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, (value shr 24) and 0xFF)

    @Test
    fun completeContainersAreAccepted() {
        assertTrue(ImageIntegrity.isComplete(jpeg(withEnd = true)))
        assertTrue(ImageIntegrity.isComplete(png(withIend = true)))
        assertTrue(ImageIntegrity.isComplete(gif(withTrailer = true)))
    }

    @Test
    fun truncatedContainersAreRejected() {
        assertFalse(ImageIntegrity.isComplete(jpeg(withEnd = false)))
        assertFalse(ImageIntegrity.isComplete(png(withIend = false)))
        assertFalse(ImageIntegrity.isComplete(gif(withTrailer = false)))
    }

    @Test
    fun webpUsesTheRiffLength() {
        val total = 12 + 16
        assertTrue(ImageIntegrity.isComplete(webp(declaredSize = total - 8)))
        assertFalse(ImageIntegrity.isComplete(webp(declaredSize = total + 8)))
    }

    @Test
    fun unknownContainersAreLeftToTheDecoder() {
        assertTrue(ImageIntegrity.isComplete(file("not an image".map { it.code }.toIntArray())))
        assertFalse(ImageIntegrity.isComplete(file(intArrayOf())))
    }
}
