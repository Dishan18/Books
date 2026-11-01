package io.github.dishan18.books.manga.reader

import kotlin.math.ceil
import kotlin.math.max

/** A horizontal band of a source image: rows [top, bottom) shown [displayHeight] pixels tall. */
data class Strip(val top: Int, val bottom: Int, val displayHeight: Int)

/**
 * Splits pages into strips no taller than about one screen, so even a 20,000-pixel webtoon panel is
 * decoded a screen at a time at display resolution instead of as one enormous bitmap.
 */
object StripPlanner {

    fun displayHeight(imageWidth: Int, imageHeight: Int, viewportWidth: Int): Int =
        max(1, (imageHeight.toLong() * viewportWidth / max(1, imageWidth)).toInt())

    fun plan(imageWidth: Int, imageHeight: Int, viewportWidth: Int, maxStripHeight: Int): List<Strip> {
        require(imageWidth > 0 && imageHeight > 0 && viewportWidth > 0 && maxStripHeight > 0)
        val totalDisplay = displayHeight(imageWidth, imageHeight, viewportWidth)
        val count = max(1, ceil(totalDisplay.toDouble() / maxStripHeight).toInt())
        // Divide source rows and display rows independently so both add up exactly.
        return (0 until count).map { i ->
            val top = (imageHeight.toLong() * i / count).toInt()
            val bottom = (imageHeight.toLong() * (i + 1) / count).toInt()
            val displayTop = (totalDisplay.toLong() * i / count).toInt()
            val displayBottom = (totalDisplay.toLong() * (i + 1) / count).toInt()
            Strip(top, bottom, max(1, displayBottom - displayTop))
        }
    }

    /** Largest power-of-two subsampling that still decodes at least [viewportWidth] pixels wide. */
    fun sampleSize(imageWidth: Int, viewportWidth: Int): Int {
        var sample = 1
        while (imageWidth / (sample * 2) >= viewportWidth) sample *= 2
        return sample
    }
}
