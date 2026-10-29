package com.ppxb.miwaku.reader.image

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.ui.reader.ScrambleTransformation
import java.io.File

internal object PageImageRequests {
    /**
     * [data] is whatever Coil can fetch: a cached file today, an archive entry once the CBZ fetcher
     * exists. [cacheKey] must identify that source, because the memory cache is keyed by it and a
     * rewritten page has to produce a different key.
     */
    fun build(
        context: Context,
        image: PageImage,
        data: Any,
        cacheKey: String,
        widthPx: Int,
        attempt: Int,
    ): ImageRequest {
        val decodeWidth = if (image.splitCount > 0) 0 else widthPx
        return ImageRequest.Builder(context)
            .data(data)
            .apply {
                if (image.splitCount > 0) {
                    size(Size.ORIGINAL)
                    transformations(ScrambleTransformation(image.splitCount))
                } else {
                    size(Dimension.Pixels(widthPx), Dimension.Undefined)
                }
            }
            .memoryCacheKey("$cacheKey:${image.splitCount}:$decodeWidth:$attempt")
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }

    fun build(
        context: Context,
        image: PageImage,
        file: File,
        widthPx: Int,
        attempt: Int,
    ): ImageRequest = build(context, image, file, "${file.absolutePath}:${file.length()}", widthPx, attempt)
}
