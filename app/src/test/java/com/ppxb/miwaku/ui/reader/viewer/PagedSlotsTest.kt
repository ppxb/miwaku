package com.ppxb.miwaku.ui.reader.viewer

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import org.junit.Assert.assertEquals
import org.junit.Test

class PagedSlotsTest {

    private fun page(
        chapterId: String,
        index: Int,
        width: Int = 800,
    ): ReaderPage =
        ReaderPage(
            image =
                PageImage(
                    chapterId = chapterId,
                    fileName = "p$index.webp",
                    index = index,
                    splitCount = 0,
                    url = "https://example.test/$chapterId/$index.webp",
                ),
            size = PageSize(width, 1200),
        )

    private fun kind(slot: PagedSlot): String =
        when (slot) {
            is PagedSlot.Pages -> "pages:${slot.pages.size}"
            is PagedSlot.Transition -> "transition"
        }

    @Test
    fun transitionIsInsertedBetweenChapters() {
        val pages = listOf(page("c1", 0), page("c1", 1), page("c2", 0))
        val slots = buildPagedSlots(pages, PageLayout.Single, mapOf("c1" to "第一话", "c2" to "第二话"))

        assertEquals(listOf("pages:1", "pages:1", "transition", "pages:1"), slots.map(::kind))
        val transition = slots[2] as PagedSlot.Transition
        assertEquals("第一话", transition.from)
        assertEquals("第二话", transition.to)
    }

    @Test
    fun noTransitionInsideOneChapter() {
        val pages = listOf(page("c1", 0), page("c1", 1), page("c1", 2))
        val slots = buildPagedSlots(pages, PageLayout.Single, emptyMap())
        assertEquals(listOf("pages:1", "pages:1", "pages:1"), slots.map(::kind))
    }

    @Test
    fun doubleLayoutKeepsPairsAndStillBreaksAtTheChapter() {
        val pages = listOf(page("c1", 0), page("c1", 1), page("c2", 0), page("c2", 1))
        val slots = buildPagedSlots(pages, PageLayout.Double, emptyMap())
        assertEquals(listOf("pages:2", "transition", "pages:2"), slots.map(::kind))
    }

    @Test
    fun transitionSharesItsPageIndexWithTheFollowingScreen() {
        val pages = listOf(page("c1", 0), page("c1", 1), page("c2", 0))
        val slots = buildPagedSlots(pages, PageLayout.Single, emptyMap())
        assertEquals(listOf(0, 1, 2, 2), pagedSlotStarts(slots))
    }

    @Test
    fun seekingNeverLandsOnATransition() {
        val pages = listOf(page("c1", 0), page("c1", 1), page("c2", 0))
        val slots = buildPagedSlots(pages, PageLayout.Single, emptyMap())
        val starts = pagedSlotStarts(slots)
        // Page 2 is the first page of chapter two: seeking there must pick the page screen, not the
        // transition that shares its start index.
        val target = starts.indexOfLast { it <= 2 }
        assertEquals("pages:1", kind(slots[target]))
        assertEquals(2, starts[target])
    }
}
