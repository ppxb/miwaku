package com.ppxb.miwaku.reader.image

import android.content.Context
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.ui.screen.reader.ScrambleDecoder
import java.io.File

internal object PageImageRequests {
    fun build(
        context: Context,
        image: PageImage,
        file: File,
        widthPx: Int,
        attempt: Int,
    ): ImageRequest {
        val decodeWidth = widthPx
        val budget = if (AppGraph.settings.lowMemory.value)
            minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
        else minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)
        return ImageRequest.Builder(context)
            .data(file)
            .apply {
                if (image.splitCount > 0) {
                    size(Size.ORIGINAL)
                    // The custom decoder samples original strip regions into a bounded output.
                    maxBitmapSize(Size.ORIGINAL)
                    decoderFactory { _, _, _ -> ScrambleDecoder(file, image.splitCount, widthPx, budget) }
                } else {
                    size(Dimension.Pixels(widthPx), Dimension.Undefined)
                }
            }
            .memoryCacheKey("region-v1:${file.absolutePath}:${file.length()}:${image.splitCount}:$decodeWidth:$attempt")
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }
}
