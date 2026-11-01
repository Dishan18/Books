package io.github.dishan18.books.manga

import io.github.dishan18.books.manga.source.SourceType
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SeriesDetails
import io.github.dishan18.books.manga.source.api.SeriesPage
import io.github.dishan18.books.manga.source.api.SourceChapter
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.SourcePage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory source for tests. Pages are "<chapter>/<index>@<generation>"; a page list refresh bumps
 * the generation, which lets tests simulate expiring URLs.
 */
class FakeSource(override val id: String = "fake", private val pagesPerChapter: Int = 5) : MangaSource {
    override val name = "Fake"
    override val type = SourceType.MANGADEX
    override val isOnline = true
    override val supportsLatest = false

    val fetches = AtomicInteger(0)
    val pageListRequests = AtomicInteger(0)

    /** Return an error for a fetch of this URL (consumed per call), or null to succeed. */
    @Volatile var failure: (String) -> SourceError? = { null }
    @Volatile var beforeFetch: suspend (SourcePage) -> Unit = {}

    override suspend fun browse(listing: Listing, page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(query: String, page: Int) = SeriesPage(emptyList(), false)
    override suspend fun details(seriesKey: String) = SeriesDetails(seriesKey, "Series $seriesKey", null, null, null, null, io.github.dishan18.books.manga.data.SeriesStatus.UNKNOWN, emptyList(), null)
    override suspend fun chapters(seriesKey: String) = (1..3).map { SourceChapter("c$it", "Chapter $it", it.toFloat(), null, null, null, pagesPerChapter) }

    override suspend fun pages(seriesKey: String, chapterKey: String): List<SourcePage> {
        val generation = pageListRequests.incrementAndGet()
        return List(pagesPerChapter) { SourcePage(it, "$chapterKey/$it@$generation") }
    }

    override suspend fun fetchPage(page: SourcePage, destination: File, onProgress: (Long, Long) -> Unit) {
        beforeFetch(page)
        fetches.incrementAndGet()
        failure(page.url)?.let { throw SourceException(it) }
        destination.parentFile?.mkdirs()
        destination.writeText("image ${page.url}")
    }
}
