package io.github.dishan18.books.manga.reader

import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.download.DownloadStorage
import io.github.dishan18.books.manga.source.SourceRegistry
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.SourcePage
import io.github.dishan18.books.manga.source.api.toSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A chapter opened for reading: page count plus a way to get each page as a local file. */
sealed class LoadedChapter(val chapter: MangaChapter) {
    abstract val pageCount: Int

    class Downloaded(chapter: MangaChapter, val files: List<File>) : LoadedChapter(chapter) {
        override val pageCount get() = files.size
    }

    class FromSource(
        chapter: MangaChapter,
        val series: MangaSeries,
        val source: MangaSource,
        initialPages: List<SourcePage>,
    ) : LoadedChapter(chapter) {
        internal val lock = Mutex()
        internal var pages = initialPages
        internal var refreshed = false
        override val pageCount get() = pages.size
    }
}

/**
 * Resolves chapter pages for the reader. Downloaded chapters are read straight from disk; others
 * are fetched through their source into a size-bounded page cache.
 */
class PageLoader(
    private val registry: SourceRegistry,
    private val storage: DownloadStorage,
    private val cacheRoot: File,
    private val maxCacheBytes: Long = 400L * 1024 * 1024,
) {
    suspend fun open(series: MangaSeries, chapter: MangaChapter): LoadedChapter {
        storage.completePages(series.id, chapter.id)?.let { return LoadedChapter.Downloaded(chapter, it) }
        val source = registry.require(series.sourceId)
        val pages = source.pages(series.sourceKey, chapter.sourceKey)
        if (pages.isEmpty()) throw SourceException(SourceError.Other("This chapter has no pages"))
        return LoadedChapter.FromSource(chapter, series, source, pages)
    }

    /** The page as a local file, fetching it if needed. [onProgress] gets 0..1 or -1 if unknown. */
    suspend fun file(loaded: LoadedChapter, index: Int, onProgress: (Float) -> Unit): File = when (loaded) {
        is LoadedChapter.Downloaded -> loaded.files[index]
        is LoadedChapter.FromSource -> {
            val file = cacheFile(loaded.chapter.id, index)
            if (file.isFile && file.length() > 0) {
                file.setLastModified(System.currentTimeMillis())
                file
            } else {
                fetch(loaded, index, file, onProgress)
                file
            }
        }
    }

    private suspend fun fetch(loaded: LoadedChapter.FromSource, index: Int, file: File, onProgress: (Float) -> Unit) {
        var attempt = 0
        while (true) {
            attempt++
            val page = loaded.lock.withLock { loaded.pages.getOrNull(index) } ?: throw SourceException(SourceError.NotFound)
            try {
                loaded.source.fetchPage(page, file) { read, total -> onProgress(if (total > 0) read.toFloat() / total else -1f) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toSourceException().error
                val expired = error == SourceError.Forbidden || error == SourceError.NotFound || error == SourceError.Http(410)
                if (expired && refreshOnce(loaded)) continue
                val retryable = error == SourceError.Timeout || error == SourceError.Unreachable ||
                    (error is SourceError.Http && error.code >= 500)
                if (!retryable || attempt >= 3) throw e.toSourceException()
                delay(800L * attempt)
            }
        }
    }

    /** Short-lived page URLs (MangaDex@Home) are re-requested once per chapter. */
    private suspend fun refreshOnce(loaded: LoadedChapter.FromSource): Boolean = loaded.lock.withLock {
        if (loaded.refreshed) return false
        loaded.refreshed = true
        loaded.pages = loaded.source.pages(loaded.series.sourceKey, loaded.chapter.sourceKey)
        true
    }

    private fun cacheFile(chapterId: Long, index: Int) = File(cacheRoot, "$chapterId/$index.img")

    /** Deletes the least recently used cached chapters until the cache fits its budget. */
    suspend fun trimCache() = withContext(Dispatchers.IO) {
        val chapters = cacheRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .map { dir -> Triple(dir, dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L, dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }) }
            .sortedBy { it.second }
        var total = chapters.sumOf { it.third }
        for ((dir, _, size) in chapters) {
            if (total <= maxCacheBytes) break
            dir.deleteRecursively()
            total -= size
        }
    }
}
