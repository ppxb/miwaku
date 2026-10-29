package com.ppxb.miwaku.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation
import com.ppxb.miwaku.core.data.image.ReaderPerf
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mirrors jm-boom's reader decoder: reorder the original rows, then downscale the result so
 * decoding at full resolution does not leave a full-size copy in memory.
 */
internal class ScrambleTransformation(private val count: Int) : Transformation {
    override val cacheKey: String = "scramble-$count-$MAX_OUTPUT_HEIGHT"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (count <= 1 || input.width <= 0 || input.height <= 0) return input

        val started = ReaderPerf.mark()
        val width = input.width
        val height = input.height
        val scale = min(1f, MAX_OUTPUT_HEIGHT.toFloat() / height)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val output = createBitmap(targetWidth, targetHeight, input.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val over = height % count
        val move = height / count

        for (i in 0 until count) {
            val sourceHeight = if (i == 0) move + over else move
            val sourceTop = height - move * (i + 1) - over
            val destTop = (if (i == 0) 0 else move * i + over) * scale
            val destBottom = ((if (i == 0) 0 else move * i + over) + sourceHeight) * scale
            canvas.drawBitmap(
                input,
                Rect(0, sourceTop, width, sourceTop + sourceHeight),
                Rect(0, destTop.roundToInt(), targetWidth, destBottom.roundToInt()),
                paint,
            )
        }
        ReaderPerf.log {
            "scramble count=$count in=${width}x$height out=${targetWidth}x$targetHeight ms=${ReaderPerf.mark() - started}"
        }
        return output
    }

    private companion object {
        const val MAX_OUTPUT_HEIGHT = 4096
    }
}
