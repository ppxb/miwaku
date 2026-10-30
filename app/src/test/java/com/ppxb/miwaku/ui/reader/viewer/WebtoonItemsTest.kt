package com.ppxb.miwaku.ui.reader.viewer

import com.ppxb.miwaku.core.data.image.PageImage
import com.ppxb.miwaku.core.data.image.PageSize
import com.ppxb.miwaku.ui.reader.model.ReaderPage
import org.junit.Assert.assertEquals
import org.junit.Test

class WebtoonItemsTest {

    private fun page(chapterId: String, index: Int): ReaderPage =
        ReaderPage(
            image =
                PageImage(
                    chapterId = chapterId,
                    fileName = "p$index.webp",
                    index = index,
                    splitCount = 0,
                    url = "https://example.test/$chapterId/$index.webp",
                ),
            size = PageSize(800, 1200),
        )

    private fun pages(): List<ReaderPage> =
        listOf(page("c1", 0), page("c1", 1), page("c2", 0), page("c2", 1))

    @Test
    fun transitionIsInsertedOnlyWhereTheChapterChanges() {
        val items = buildWebtoonItems(pages(), mapOf("c1" to "第一话", "c2" to "第二话"), hasFooter = false)

        assertEquals(5, items.size)
        assertEquals(
            listOf("page", "page", "transition", "page", "page"),
            items.map {
                when (it) {
                    is WebtoonItem.Page -> "page"
                    is WebtoonItem.Transition -> "transition"
                    is WebtoonItem.Footer -> "footer"
                }
            },
        )
        val transition = items[2] as WebtoonItem.Transition
        assertEquals("第一话", transition.from)
        assertEquals("第二话", transition.to)
        // The transition reports the page it precedes, so progress never lands on a divider.
        assertEquals(2, transition.pageIndex)
    }

    @Test
    fun footerIsAppendedAndPointsAtTheLastPage() {
        val items = buildWebtoonItems(pages(), emptyMap(), hasFooter = true)

        val footer = items.last() as WebtoonItem.Footer
        assertEquals(3, footer.pageIndex)
        // 4 pages + the chapter transition + the footer.
        assertEquals(6, items.size)
    }

    @Test
    fun pageIndicesMatchTheWindowOrder() {
        val items = buildWebtoonItems(pages(), emptyMap(), hasFooter = true)
        assertEquals(
            listOf(0, 1, 2, 2, 3, 3),
            items.map { it.pageIndex },
        )
    }

    @Test
    fun emptyWindowHasNoItems() {
        assertEquals(emptyList<WebtoonItem>(), buildWebtoonItems(emptyList(), emptyMap(), hasFooter = true))
    }
}
