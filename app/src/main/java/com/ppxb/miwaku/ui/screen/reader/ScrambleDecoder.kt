package com.ppxb.miwaku.ui.screen.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sample each original strip separately; sampling a scrambled image first loses strip boundaries. */
internal class ScrambleDecoder(
    private val file: File,
    private val count: Int,
    private val widthPx: Int,
    private val memoryBudgetBytes: Long = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4),
) : Decoder {
    var peakBitmapBytes: Long = 0
        private set
    var strategy: String = "regions"
        private set
    override suspend fun decode(): DecodeResult {
        @Suppress("DEPRECATION")
        val decoder = checkNotNull(BitmapRegionDecoder.newInstance(file.path, false))
        var output: Bitmap? = null
        var fullPng: Bitmap? = null
        try {
            val width = decoder.width
            val height = decoder.height
            val outputBudget = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
            val budgetScale = sqrt(outputBudget.toDouble() / (width.toDouble() * height * 4)).toFloat()
            val scale = minOf(1f, widthPx.coerceAtLeast(1).toFloat() / width, 4096f / height).coerceAtMost(budgetScale)
            val outWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val outHeight = (height * scale).roundToInt().coerceAtLeast(1)
            output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            peakBitmapBytes = output.allocationByteCount.toLong()
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var sample = 1
            while (sample < (1 shl 29) && sample * 2 <= 1f / scale) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            // PNG region decoding repeatedly scans preceding rows. Decode it once when both
            // bitmaps fit the explicit budget; JPEG keeps its faster, smaller region path.
            val signature = ByteArray(8)
            val png = file.inputStream().use { it.read(signature) == 8 } &&
                signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val fullBytes = (width.toLong() * height + outWidth.toLong() * outHeight) * 4
            if (png && fullBytes <= memoryBudgetBytes) {
                strategy = "bounded-png"
                fullPng = checkNotNull(decoder.decodeRegion(Rect(0, 0, width, height), BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }))
                peakBitmapBytes += fullPng.allocationByteCount
            }
            val strips = count.coerceAtLeast(1)
            val remainder = height % strips
            val step = height / strips
            for (index in 0 until strips) {
                currentCoroutineContext().ensureActive()
                val stripHeight = if (index == 0) step + remainder else step
                if (stripHeight == 0) continue
                val sourceTop = height - step * (index + 1) - remainder
                val destTop = if (index == 0) 0 else step * index + remainder
                val bitmap = fullPng ?: checkNotNull(decoder.decodeRegion(Rect(0, sourceTop, width, sourceTop + stripHeight), options))
                peakBitmapBytes = maxOf(peakBitmapBytes, output.allocationByteCount.toLong() + bitmap.allocationByteCount)
                try {
                    canvas.drawBitmap(bitmap, if (fullPng == null) null else Rect(0, sourceTop, width, sourceTop + stripHeight),
                        Rect(0, (destTop * scale).roundToInt(), outWidth, ((destTop + stripHeight) * scale).roundToInt()), paint)
                } finally { if (fullPng == null) bitmap.recycle() }
            }
            return DecodeResult(output.asImage(), isSampled = scale < 1f).also { output = null }
        } finally {
            output?.recycle()
            fullPng?.recycle()
            decoder.recycle()
        }
    }
}
