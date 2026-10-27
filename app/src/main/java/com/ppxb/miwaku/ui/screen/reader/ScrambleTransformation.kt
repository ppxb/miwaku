package com.ppxb.miwaku.ui.screen.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation

internal class ScrambleTransformation(private val count: Int) : Transformation {
    override val cacheKey: String = "scramble-$count"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (count <= 1 || input.width <= 0 || input.height <= 0) return input

        val width = input.width
        val height = input.height
        val config = input.config ?: Bitmap.Config.ARGB_8888
        val output = createBitmap(width, height, config)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val over = height % count
        val move = height / count

        for (i in 0 until count) {
            val sourceHeight = if (i == 0) move + over else move
            val sourceTop = height - move * (i + 1) - over
            val destTop = if (i == 0) 0 else move * i + over
            canvas.drawBitmap(
                input,
                Rect(0, sourceTop, width, sourceTop + sourceHeight),
                Rect(0, destTop, width, destTop + sourceHeight),
                paint,
            )
        }
        return output
    }
}
