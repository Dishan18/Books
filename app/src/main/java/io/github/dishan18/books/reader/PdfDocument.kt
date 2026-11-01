package io.github.dishan18.books.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders PDF pages to bitmaps off the main thread. [PdfRenderer] can only work on one page at a
 * time, so every call goes through a single-threaded dispatcher.
 */
class PdfDocument(file: File) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Width / height of each page, filled in as pages are measured. */
    private val aspectRatios = FloatArray(pageCount)

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cachedAspectRatio(index: Int): Float? = aspectRatios[index].takeIf { it > 0f }

    suspend fun aspectRatio(index: Int): Float = cachedAspectRatio(index) ?: withContext(dispatcher) {
        if (closed) return@withContext 1f
        cachedAspectRatio(index) ?: renderer.openPage(index).use { page ->
            (page.width.toFloat() / page.height).also { aspectRatios[index] = it }
        }
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get(key(index, width))

    /** Renders page [index] at [width] pixels wide. */
    suspend fun render(index: Int, width: Int): Bitmap? {
        cached(index, width)?.let { return it }
        return withContext(dispatcher) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                aspectRatios[index] = page.width.toFloat() / page.height
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key(index, width), bitmap)
                bitmap
            }
        }
    }

    /**
     * Renders the on-screen part of a zoomed page into a [width]×[height] bitmap, with the page
     * drawn [pageWidth] pixels wide and its top-left corner at ([left], [top]). Areas outside the
     * page stay white, which the theme filters turn into the reader background.
     */
    suspend fun renderRegion(index: Int, width: Int, height: Int, left: Float, top: Float, pageWidth: Float): Bitmap? =
        withContext(dispatcher) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val scale = pageWidth / page.width
                val transform = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(left, top)
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }

    fun close() {
        // Wait for any render in flight before closing the renderer underneath it.
        CoroutineScope(dispatcher).launch {
            closed = true
            cache.evictAll()
            renderer.close()
            fd.close()
        }
    }

    private fun key(index: Int, width: Int) = "$index@$width"

    private companion object {
        fun cacheBytes() = (Runtime.getRuntime().maxMemory() / 6).toInt()
    }
}
