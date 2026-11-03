package io.github.dishan18.books.office.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import io.github.dishan18.books.office.OfficePackage
import io.github.dishan18.books.reader.PageDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A presentation's slides as pages for the PDF reader, drawn off the main thread one at a time. */
class SlideDocument private constructor(private val pkg: OfficePackage, private val presentation: Presentation) : PageDocument {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val images = Images(pkg)
    private val renderer = SlideRenderer(presentation, images)
    private val aspect = presentation.width / presentation.height
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override val pageCount: Int = presentation.slides.size

    override fun cachedAspectRatio(index: Int): Float = aspect

    override suspend fun aspectRatio(index: Int): Float = aspect

    override fun cached(index: Int, width: Int): Bitmap? = cache.get(key(index, width))

    override suspend fun render(index: Int, width: Int): Bitmap? {
        cached(index, width)?.let { return it }
        return withContext(dispatcher) {
            if (closed) return@withContext null
            val height = (width / aspect).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(bitmap), index, width / presentation.width)
            cache.put(key(index, width), bitmap)
            bitmap
        }
    }

    override suspend fun renderRegion(index: Int, width: Int, height: Int, left: Float, top: Float, pageWidth: Float): Bitmap? =
        withContext(dispatcher) {
            if (closed) return@withContext null
            // Outside the slide stays transparent, so the reader's background shows there.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(left, top)
            renderer.draw(canvas, index, pageWidth / presentation.width)
            bitmap
        }

    override fun trim(background: Boolean) {
        if (background) {
            cache.evictAll()
            images.evictAll()
        } else {
            cache.trimToSize(cache.maxSize() / 4)
            images.trimToSize(images.maxSize() / 4)
        }
    }

    override fun close() {
        // Wait for any render in flight before closing the file underneath it.
        CoroutineScope(dispatcher).launch {
            closed = true
            cache.evictAll()
            images.evictAll()
            pkg.close()
        }
    }

    private fun key(index: Int, width: Int) = "$index@$width"

    /** Pictures decoded at no more than twice the size they're drawn at, so large photos don't fill memory. */
    internal class Images(private val pkg: OfficePackage) : LruCache<String, Bitmap>(cacheBytes() / 2), ImageSource {
        private val bounds = HashMap<String, IntArray?>()

        override fun sizeOf(key: String, value: Bitmap) = value.byteCount

        override fun bitmap(part: String, width: Int, height: Int): Bitmap? {
            val size = bounds.getOrPut(part) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    pkg.open(part, MAX_IMAGE_BYTES).use { BitmapFactory.decodeStream(it, null, options) }
                    if (options.outWidth > 0 && options.outHeight > 0) intArrayOf(options.outWidth, options.outHeight) else null
                }.getOrNull()
            } ?: return null
            var sample = 1
            val wanted = max(width, 1).coerceAtMost(MAX_DECODE)
            val wantedH = max(height, 1).coerceAtMost(MAX_DECODE)
            while (size[0] / (sample * 2) >= wanted && size[1] / (sample * 2) >= wantedH) sample *= 2
            while (size[0] / sample > MAX_DECODE || size[1] / sample > MAX_DECODE) sample *= 2
            val key = "$part@$sample"
            get(key)?.let { return it }
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                pkg.open(part, MAX_IMAGE_BYTES).use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull() ?: return null
            put(key, bitmap)
            return bitmap
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        private const val MAX_DECODE = 4096

        private fun cacheBytes() = (Runtime.getRuntime().maxMemory() / 6).toInt()

        /** Opens and reads [file]. Throws [io.github.dishan18.books.office.OfficeFileException] for files it can't show. */
        fun open(file: File): SlideDocument {
            val pkg = OfficePackage(file)
            return try {
                SlideDocument(pkg, PresentationReader(pkg).read())
            } catch (e: Exception) {
                pkg.close()
                throw e
            }
        }

        /** Draws slide [index] of an already-read presentation, for covers. */
        internal fun renderOnce(pkg: OfficePackage, presentation: Presentation, index: Int, width: Int): Bitmap {
            val height = (width * presentation.height / presentation.width).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            SlideRenderer(presentation, Images(pkg)).draw(Canvas(bitmap), index, width / presentation.width)
            return bitmap
        }
    }
}
