package io.github.dishan18.books.manga.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Width and height of an image file, read without decoding pixels. */
fun imageSize(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * Decodes page strips with [BitmapRegionDecoder] off the main thread. Decoded strips live in a
 * byte-bounded LRU so scrolling back is instant without unbounded memory use; region decoders
 * are cached per file.
 */
class StripDecoder(maxCacheBytes: Int = defaultCacheBytes()) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(3)

    private val bitmaps = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val decoders = object : LruCache<String, BitmapRegionDecoder>(8) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: BitmapRegionDecoder, newValue: BitmapRegionDecoder?) {
            synchronized(oldValue) { oldValue.recycle() }
        }
    }

    fun cached(key: String): Bitmap? = bitmaps.get(key)

    /**
     * Decodes [strip] of [file] subsampled by [sampleSize], then scales it to [targetWidth] if it's
     * still wider: a strip is shown exactly that wide, so extra pixels only cost memory and slower
     * GPU uploads (a 1600 px page is 2.2x the bytes of the same strip at 1080 px). Null if the file
     * can't be decoded.
     */
    suspend fun decode(key: String, file: File, width: Int, strip: Strip, sampleSize: Int, targetWidth: Int): Bitmap? {
        bitmaps.get(key)?.let { return it }
        return withContext(dispatcher) {
            bitmaps.get(key) ?: runCatching { decodeRegion(file, width, strip, sampleSize) }
                .recoverCatching { decodeFallback(file, width, strip, sampleSize) }
                .getOrNull()
                ?.let { fitWidth(it, targetWidth) }
                ?.also {
                    // Start the texture upload now, off the main thread, instead of on first draw.
                    it.prepareToDraw()
                    bitmaps.put(key, it)
                }
        }
    }

    private fun fitWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || bitmap.width <= targetWidth) return bitmap
        val height = (bitmap.height.toLong() * targetWidth / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun decodeRegion(file: File, width: Int, strip: Strip, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        repeat(2) {
            val decoder = synchronized(decoders) {
                decoders.get(file.path)?.takeIf { !it.isRecycled }
                    ?: newDecoder(file).also { decoders.put(file.path, it) }
            }
            synchronized(decoder) {
                if (!decoder.isRecycled) {
                    return decoder.decodeRegion(Rect(0, strip.top, width, strip.bottom), options)
                        ?: error("Region decode failed")
                }
            }
        }
        error("Decoder unavailable")
    }

    private fun newDecoder(file: File): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(file.path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.path, false)
        } ?: error("Region decoder unavailable")

    /** For formats the region decoder can't handle (e.g. GIF): decode whole, then crop. */
    private fun decodeFallback(file: File, width: Int, strip: Strip, sampleSize: Int): Bitmap {
        val full = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: error("Unsupported image")
        val scale = full.width.toFloat() / width
        val top = (strip.top * scale).toInt().coerceIn(0, full.height - 1)
        val bottom = (strip.bottom * scale).toInt().coerceIn(top + 1, full.height)
        return Bitmap.createBitmap(full, 0, top, full.width, bottom - top)
    }

    fun clear() {
        bitmaps.evictAll()
        synchronized(decoders) { decoders.evictAll() }
    }

    /** While the reader is hidden keep only the most recent strips; in the background, nothing. */
    fun trim(background: Boolean) {
        if (background) clear() else bitmaps.trimToSize(bitmaps.maxSize() / 4)
    }

    private companion object {
        fun defaultCacheBytes() = (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(256L * 1024 * 1024).toInt()
    }
}
