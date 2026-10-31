package com.ppxb.miwaku.core.data.image

import java.io.File
import java.io.RandomAccessFile

/**
 * Cheap completeness check for a downloaded page. A body cut short mid-transfer still exposes valid
 * dimensions, so the container tail is the only reliable signal before the bytes are committed.
 * Unknown containers are accepted: the decoder stays the final judge.
 */
internal object ImageIntegrity {
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_MAGIC =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val IEND = "IEND".toByteArray(Charsets.US_ASCII)
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val GIF_END = byteArrayOf(0x3B)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val BMP_MAGIC = "BM".toByteArray(Charsets.US_ASCII)

    fun isComplete(file: File): Boolean {
        if (file.length() <= 0L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { source ->
                val length = source.length()
                val head = ByteArray(minOf(HEAD_BYTES.toLong(), length).toInt())
                source.seek(0)
                source.readFully(head)
                val tail = tailBytes(source, length)
                when {
                    head.startsWith(JPEG_MAGIC) -> tail.endsWithBytes(JPEG_END)
                    head.startsWith(PNG_MAGIC) -> tail.containsAt(PNG_IEND_FROM_END, IEND)
                    head.startsWith(GIF87) || head.startsWith(GIF89) -> tail.endsWithBytes(GIF_END)
                    head.startsWith(RIFF) && head.size >= 12 && head.copyOfRange(8, 12).contentEquals(WEBP) ->
                        length >= 8L + littleEndian(head, 4)
                    head.startsWith(BMP_MAGIC) && head.size >= 6 -> {
                        val declared = littleEndian(head, 2)
                        declared in 1..length
                    }
                    else -> true
                }
            }
        }.getOrDefault(true)
    }

    private fun tailBytes(source: RandomAccessFile, length: Long): ByteArray {
        val size = minOf(TAIL_BYTES.toLong(), length).toInt()
        val bytes = ByteArray(size)
        source.seek(length - size)
        source.readFully(bytes)
        return bytes
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.endsWithBytes(suffix: ByteArray): Boolean =
        size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

    /** [fromEnd] is the distance between the needle's last byte and the end of the file. */
    private fun ByteArray.containsAt(fromEnd: Int, needle: ByteArray): Boolean {
        val end = size - fromEnd
        val start = end - needle.size
        return start >= 0 && end <= size && needle.indices.all { this[start + it] == needle[it] }
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private const val HEAD_BYTES = 16
    private const val TAIL_BYTES = 32

    /** PNG ends with 12 bytes: [length=0][IEND][crc], so "IEND" ends 4 bytes (the CRC) before EOF. */
    private const val PNG_IEND_FROM_END = 4
}
