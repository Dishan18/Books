package io.github.dishan18.books.manga.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripPlannerTest {

    @Test
    fun normalPageIsOneStrip() {
        val strips = StripPlanner.plan(imageWidth = 1000, imageHeight = 1400, viewportWidth = 1080, maxStripHeight = 2400)
        assertEquals(listOf(Strip(0, 1400, 1512)), strips)
    }

    @Test
    fun tallWebtoonImageIsSplitIntoScreenSizedStrips() {
        val strips = StripPlanner.plan(imageWidth = 800, imageHeight = 20_000, viewportWidth = 1080, maxStripHeight = 2400)
        val displayTotal = StripPlanner.displayHeight(800, 20_000, 1080)

        assertEquals(27_000, displayTotal)
        assertEquals(12, strips.size)
        assertTrue(strips.all { it.displayHeight <= 2400 })
        // Strips tile the image exactly: no gaps, no overlap, nothing lost to rounding.
        assertEquals(0, strips.first().top)
        assertEquals(20_000, strips.last().bottom)
        strips.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top) }
        assertEquals(displayTotal, strips.sumOf { it.displayHeight })
    }

    @Test
    fun oddSizesStillAddUp() {
        val strips = StripPlanner.plan(imageWidth = 723, imageHeight = 9_999, viewportWidth = 1344, maxStripHeight = 2992)
        assertEquals(StripPlanner.displayHeight(723, 9_999, 1344), strips.sumOf { it.displayHeight })
        assertEquals(9_999, strips.last().bottom)
    }

    @Test
    fun sampleSizeNeverDecodesNarrowerThanTheScreen() {
        assertEquals(1, StripPlanner.sampleSize(imageWidth = 1080, viewportWidth = 1080))
        assertEquals(1, StripPlanner.sampleSize(imageWidth = 2000, viewportWidth = 1080))
        assertEquals(2, StripPlanner.sampleSize(imageWidth = 2200, viewportWidth = 1080))
        assertEquals(4, StripPlanner.sampleSize(imageWidth = 4400, viewportWidth = 1080))
        assertEquals(1, StripPlanner.sampleSize(imageWidth = 500, viewportWidth = 1080))
    }
}
