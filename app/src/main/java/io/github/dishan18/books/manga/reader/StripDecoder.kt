package io.github.dishan18.books.manga.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
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

    /** Decodes [strip] of [file] subsampled by [sampleSize]; null if the file can't be decoded. */
    suspend fun decode(key: String, file: File, width: Int, strip: Strip, sampleSize: Int): Bitmap? {
        bitmaps.get(key)?.let { return it }
        return withContext(dispatcher) {
            bitmaps.get(key) ?: runCatching { decodeRegion(file, width, strip, sampleSize) }
                .recoverCatching { decodeFallback(file, width, strip, sampleSize) }
                .getOrNull()
                ?.also { bitmaps.put(key, it) }
        }
    }

    private fun decodeRegion(file: File, width: Int, strip: Strip, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        repeat(2) {
            val decoder = synchronized(decoders) {
                decoders.get(file.path)?.takeIf { !it.isRecycled }
                    ?: BitmapRegionDecoder.newInstance(file.path).also { decoders.put(file.path, it) }
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

    private companion object {
        fun defaultCacheBytes() = (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(256L * 1024 * 1024).toInt()
    }
}
