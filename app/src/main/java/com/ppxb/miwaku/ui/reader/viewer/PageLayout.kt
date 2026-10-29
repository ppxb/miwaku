package com.ppxb.miwaku.ui.reader.viewer

import com.ppxb.miwaku.ui.reader.model.ReaderPage

/**
 * How pages are laid out on one screen. This is a layout of the paged viewer, not a reading mode of
 * its own: double page reuses the same pager and only changes how many pages a slot holds.
 */
sealed interface PageLayout {
    data object Single : PageLayout

    data object Double : PageLayout

    companion object {
        /**
         * Groups [pages] into screens. In [Double] two consecutive pages share a screen unless one of
         * them is a spread, which always takes a screen alone, or the pair would cross a chapter
         * boundary; a trailing odd page stays alone.
         */
        fun slots(
            pages: List<ReaderPage>,
            layout: PageLayout,
        ): List<List<ReaderPage>> =
            when (layout) {
                Single -> pages.map(::listOf)
                Double -> pair(pages)
            }

        private fun pair(pages: List<ReaderPage>): List<List<ReaderPage>> {
            val slots = ArrayList<List<ReaderPage>>(pages.size / 2 + 1)
            var index = 0
            while (index < pages.size) {
                val current = pages[index]
                val next = pages.getOrNull(index + 1)
                val canPair =
                    next != null &&
                        next.chapterId == current.chapterId &&
                        !current.isSpread &&
                        !next.isSpread
                if (canPair) {
                    slots += listOf(current, next)
                    index += 2
                } else {
                    slots += listOf(current)
                    index += 1
                }
            }
            return slots
        }

        /** First page index of every slot, so a slot can be reported as a page for progress. */
        fun slotStarts(slots: List<List<ReaderPage>>): List<Int> {
            var index = 0
            return slots.map { slot -> index.also { index += slot.size } }
        }
    }
}
