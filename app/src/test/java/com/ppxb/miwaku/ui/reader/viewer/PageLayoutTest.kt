package com.ppxb.miwaku.ui.reader.viewer

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {

    private fun page(
        index: Int,
        width: Int = 800,
        height: Int = 1200,
    ): ReaderPage =
        ReaderPage(
            image =
                PageImage(
                    chapterId = "c1",
                    fileName = "page-$index.webp",
                    index = index,
                    splitCount = 0,
                    url = "https://example.test/$index.webp",
                ),
            size = PageSize(width, height),
        )

    private fun spread(index: Int): ReaderPage = page(index, width = 1600, height = 1200)

    private fun List<List<ReaderPage>>.indices(): List<List<Int>> =
        map { slot -> slot.map { it.image.index } }

    @Test
    fun singleKeepsOnePagePerSlot() {
        val slots = PageLayout.slots(listOf(page(0), page(1), page(2)), PageLayout.Single)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), slots.indices())
    }

    @Test
    fun doublePairsConsecutivePages() {
        val slots = PageLayout.slots(listOf(page(0), page(1), page(2), page(3)), PageLayout.Double)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), slots.indices())
    }

    @Test
    fun doubleLeavesATrailingOddPageAlone() {
        val slots = PageLayout.slots(listOf(page(0), page(1), page(2)), PageLayout.Double)
        assertEquals(listOf(listOf(0, 1), listOf(2)), slots.indices())
    }

    @Test
    fun spreadTakesItsOwnSlot() {
        val slots = PageLayout.slots(listOf(page(0), spread(1), page(2)), PageLayout.Double)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), slots.indices())
    }

    @Test
    fun spreadDoesNotBreakTheFollowingPair() {
        val slots = PageLayout.slots(listOf(page(0), page(1), spread(2), page(3)), PageLayout.Double)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), slots.indices())
    }

    @Test
    fun unknownSizeStillPairs() {
        val unknown = page(0).withSize(null)
        val slots = PageLayout.slots(listOf(unknown, page(1)), PageLayout.Double)
        assertEquals(listOf(listOf(0, 1)), slots.indices())
    }

    @Test
    fun pagesNeverPairAcrossAChapterBoundary() {
        val lastOfChapter = page(0)
        val firstOfNext = page(1).copy(image = page(1).image.copy(chapterId = "c2"))
        val slots = PageLayout.slots(listOf(lastOfChapter, firstOfNext), PageLayout.Double)
        assertEquals(listOf(listOf(0), listOf(1)), slots.indices())
    }

    @Test
    fun slotStartsMapSlotsBackToPageIndices() {
        val slots = PageLayout.slots(listOf(page(0), page(1), page(2), page(3), page(4)), PageLayout.Double)
        assertEquals(listOf(0, 2, 4), PageLayout.slotStarts(slots))
    }

    @Test
    fun emptyChapterProducesNoSlots() {
        assertEquals(emptyList<List<Int>>(), PageLayout.slots(emptyList(), PageLayout.Double).indices())
    }
}
