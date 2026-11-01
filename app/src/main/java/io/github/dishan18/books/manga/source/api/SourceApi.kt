package io.github.dishan18.books.manga.source.api

import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.source.SourceType
import java.io.File

enum class Listing { POPULAR, LATEST }

/** A series as it appears in a catalog listing or search result. */
data class SourceSeries(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val author: String? = null,
    val status: SeriesStatus = SeriesStatus.UNKNOWN,
)

data class SeriesPage(val items: List<SourceSeries>, val hasNextPage: Boolean)

data class SeriesDetails(
    val key: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val coverUrl: String?,
    val status: SeriesStatus,
    val genres: List<String>,
    val webUrl: String?,
)

data class SourceChapter(
    val key: String,
    val name: String,
    val number: Float?,
    val volume: String?,
    val scanlator: String?,
    val uploadedAt: Long?,
    val pageCount: Int?,
)

/** One image of a chapter. [url] is meaningful only to the source that produced it. */
data class SourcePage(val index: Int, val url: String)

/** Browsing, searching and series metadata. */
interface CatalogProvider {
    val supportsLatest: Boolean

    suspend fun browse(listing: Listing, page: Int): SeriesPage

    suspend fun search(query: String, page: Int): SeriesPage

    suspend fun details(seriesKey: String): SeriesDetails
}

/** Chapter lists, page lists and page images. */
interface ChapterProvider {
    /** All chapters, in reading order (first chapter first). */
    suspend fun chapters(seriesKey: String): List<SourceChapter>

    /** Page list of a chapter. Online sources may return short-lived URLs; re-request on failure. */
    suspend fun pages(seriesKey: String, chapterKey: String): List<SourcePage>

    /**
     * Writes the image for [page] into [destination], replacing it. Throws [SourceException].
     * [onProgress] receives (bytesRead, totalBytes or -1).
     */
    suspend fun fetchPage(page: SourcePage, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> })
}

/** A configured content source. One instance per configured source. */
interface MangaSource : CatalogProvider, ChapterProvider {
    val id: String
    val name: String
    val type: SourceType

    /** False for sources that read files already on the device. */
    val isOnline: Boolean

    /** Extra headers needed to load this source's cover images (for example, auth). */
    fun imageHeaders(): Map<String, String> = emptyMap()
}
