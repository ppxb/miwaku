package com.ppxb.miwaku.core.data.image

import java.io.File
import java.io.FileOutputStream

/**
 * Append-only log of page sizes.
 *
 * The cache is disposable and always written by this build, so the format carries no version: a
 * record is `[u16 keyLength][key][i32 width][i32 height][i32 splitCount]`. A truncated tail (crash
 * mid-append) is ignored, and `width <= 0` is a tombstone that removes the key. Readers replay the
 * log in order, so the last record for a key wins.
 */
internal object PageSizeLog {
    data class Entry(
        val key: String,
        val width: Int,
        val height: Int,
        val splitCount: Int,
    )

    /** keyLength (2) + width/height/splitCount (12). */
    const val FIXED_BYTES = 14
    const val MAX_KEY_BYTES = 512

    fun encode(entries: List<Entry>): ByteArray {
        val out = java.io.ByteArrayOutputStream(entries.size * 48)
        for (entry in entries) {
            val key = entry.key.toByteArray(Charsets.UTF_8)
            if (key.size > MAX_KEY_BYTES) continue
            out.write(key.size shr 8)
            out.write(key.size and 0xFF)
            out.write(key)
            writeInt(out, entry.width)
            writeInt(out, entry.height)
            writeInt(out, entry.splitCount)
        }
        return out.toByteArray()
    }

    /** Decodes every complete record; a partial record at the end is dropped. */
    fun decode(bytes: ByteArray): List<Entry> {
        val entries = ArrayList<Entry>(bytes.size / 48 + 1)
        var offset = 0
        while (offset + 2 <= bytes.size) {
            val keyLength = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            offset += 2
            if (keyLength <= 0 || keyLength > MAX_KEY_BYTES) break
            if (offset + keyLength + 12 > bytes.size) break
            val key = String(bytes, offset, keyLength, Charsets.UTF_8)
            offset += keyLength
            val width = readInt(bytes, offset)
            val height = readInt(bytes, offset + 4)
            val splitCount = readInt(bytes, offset + 8)
            offset += 12
            entries += Entry(key, width, height, splitCount)
        }
        return entries
    }

    fun read(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching { decode(file.readBytes()) }.getOrDefault(emptyList())
    }

    fun append(file: File, entries: List<Entry>) {
        if (entries.isEmpty()) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write(encode(entries))
            output.flush()
        }
    }

    fun rewrite(file: File, entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temp.writeBytes(encode(entries))
            if (!temp.renameTo(file)) temp.delete()
        }.onFailure { runCatching { temp.delete() } }
    }

    private fun writeInt(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
