package io.github.dishan18.books.reader

import android.graphics.Bitmap

/** Pages the PDF reader can show: a PDF's pages, or a presentation's slides. */
interface PageDocument {
    val pageCount: Int

    /** Width / height of page [index], if already known. */
    fun cachedAspectRatio(index: Int): Float?

    suspend fun aspectRatio(index: Int): Float

    fun cached(index: Int, width: Int): Bitmap?

    /** Renders page [index] at [width] pixels wide. */
    suspend fun render(index: Int, width: Int): Bitmap?

    /**
     * Renders the on-screen part of a zoomed page into a [width]×[height] bitmap, with the page
     * drawn [pageWidth] pixels wide and its top-left corner at ([left], [top]).
     */
    suspend fun renderRegion(index: Int, width: Int, height: Int, left: Float, top: Float, pageWidth: Float): Bitmap?

    /** While the reader is hidden keep only the most recent pages; in the background, none. */
    fun trim(background: Boolean)

    fun close()
}
