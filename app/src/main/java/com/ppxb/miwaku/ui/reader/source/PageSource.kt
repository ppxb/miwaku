package com.ppxb.miwaku.ui.reader.source

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageImageStore
import java.io.File

/**
 * Where the bytes of one page come from. The reader no longer assumes "a cached file": an imported
 * CBZ serves the same page from a zip entry, and the viewer does not need to know which.
 *
 * This is the seam komikku gets from `PageLoader`; keeping it explicit is what makes the archive
 * import additive instead of another branch inside the reader.
 */
sealed interface PageSource {
    /** A plain file on disk: reader cache or a downloaded chapter. */
    data class Cached(val file: File) : PageSource

    /** An entry inside an archive such as an imported CBZ. */
    data class ArchiveEntry(val archive: File, val entry: String) : PageSource
}

/** Resolves a page to something readable, or null when the page is not available locally yet. */
fun interface PageSourceResolver {
    suspend fun resolve(image: PageImage): PageSource?
}

/**
 * The online/downloaded case: [PageImageStore] already prefers a downloaded copy over the reader
 * cache, so one lookup covers both.
 */
class DefaultPageSourceResolver(
    private val images: PageImageStore,
) : PageSourceResolver {
    override suspend fun resolve(image: PageImage): PageSource? =
        images.cachedFile(image)?.let(PageSource::Cached)
}
