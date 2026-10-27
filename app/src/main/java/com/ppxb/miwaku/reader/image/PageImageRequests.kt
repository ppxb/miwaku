package com.ppxb.miwaku.reader.image

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.ui.screen.reader.ScrambleTransformation
import java.io.File

internal object PageImageRequests {
    fun build(
        context: Context,
        image: PageImage,
        file: File,
        widthPx: Int,
        attempt: Int,
    ): ImageRequest {
        val decodeWidth = if (image.splitCount > 0) 0 else widthPx
        return ImageRequest.Builder(context)
            .data(file)
            .apply {
                if (image.splitCount > 0) {
                    size(Size.ORIGINAL)
                    transformations(ScrambleTransformation(image.splitCount))
                } else {
                    size(Dimension.Pixels(widthPx), Dimension.Undefined)
                }
            }
            .memoryCacheKey("${file.absolutePath}:${file.length()}:${image.splitCount}:$decodeWidth:$attempt")
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }
}
