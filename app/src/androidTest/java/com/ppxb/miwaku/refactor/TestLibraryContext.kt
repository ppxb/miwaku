package com.ppxb.miwaku.refactor

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal class TestLibraryContext(base: Context) : ContextWrapper(base) {
    private val id = UUID.randomUUID().toString()
    private val root = File(base.cacheDir, "reader-tests/" + id).apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "databases").apply { mkdirs() }, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("reader-test-" + id + "-" + name, mode)

    fun recycle() {
        val destination = File(baseContext.filesDir, "recycle/test-artifacts").apply { mkdirs() }
        check(root.renameTo(File(destination, id)))
    }
}

internal fun testPng(width: Int = 40, height: Int = 60): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(45, 120, 210))
    return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
    finally { bitmap.recycle() }
}

/** Generate a highly compressed large PNG without allocating its uncompressed bitmap. */
internal fun writeSolidPng(file: File, width: Int, height: Int) {
    val compressed = ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(compressed).use { stream ->
        val row = ByteArray(width * 3 + 1)
        repeat(width) { index -> row[index * 3 + 1] = 45; row[index * 3 + 2] = 120; row[index * 3 + 3] = 210.toByte() }
        repeat(height) { stream.write(row) }
    }
    java.io.DataOutputStream(file.outputStream()).use { output ->
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, bytes: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(type); update(bytes) }
            output.writeInt(bytes.size); output.write(type); output.write(bytes); output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        java.io.DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray()); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
    }
}
