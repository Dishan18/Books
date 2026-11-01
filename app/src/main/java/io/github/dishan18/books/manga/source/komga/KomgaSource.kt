package io.github.dishan18.books.manga.source.komga

import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.source.SourceType
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SeriesDetails
import io.github.dishan18.books.manga.source.api.SeriesPage
import io.github.dishan18.books.manga.source.api.SourceChapter
import io.github.dishan18.books.manga.source.api.SourcePage
import io.github.dishan18.books.manga.source.api.SourceSeries
import io.github.dishan18.books.manga.source.net.download
import io.github.dishan18.books.manga.source.net.getJson
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A Komga server the user runs themselves. [client] must already carry the server's auth
 * (see [io.github.dishan18.books.manga.source.SourceRegistry]); [authHeaders] is reused for covers.
 */
class KomgaSource(
    override val id: String,
    override val name: String,
    private val client: OkHttpClient,
    baseUrl: String,
    private val authHeaders: Map<String, String>,
) : MangaSource {
    override val type = SourceType.KOMGA
    override val isOnline = true
    override val supportsLatest = true

    private val api: HttpUrl = baseUrl.trimEnd('/').plus("/api/v1/").toHttpUrl()

    override fun imageHeaders() = authHeaders

    override suspend fun browse(listing: Listing, page: Int): SeriesPage {
        val url = when (listing) {
            Listing.POPULAR -> seriesUrl(page).addQueryParameter("sort", "metadata.titleSort,asc")
            Listing.LATEST -> api.newBuilder().addPathSegments("series/latest").pageParams(page)
        }.build()
        return seriesPage(url)
    }

    override suspend fun search(query: String, page: Int): SeriesPage =
        seriesPage(seriesUrl(page).addQueryParameter("search", query.trim()).build())

    private fun seriesUrl(page: Int) = api.newBuilder().addPathSegment("series").pageParams(page)

    private fun HttpUrl.Builder.pageParams(page: Int) = apply {
        addQueryParameter("page", (page - 1).coerceAtLeast(0).toString())
        addQueryParameter("size", PAGE_SIZE.toString())
    }

    private suspend fun seriesPage(url: HttpUrl): SeriesPage {
        val result = client.getJson(get(url), KomgaPage.serializer(KomgaSeries.serializer()))
        return SeriesPage(
            items = result.content.map {
                SourceSeries(
                    key = it.id,
                    title = it.title(),
                    coverUrl = thumbnailUrl(it.id),
                    author = it.writers(),
                    status = statusOf(it.metadata.status),
                )
            },
            hasNextPage = !result.last,
        )
    }

    override suspend fun details(seriesKey: String): SeriesDetails {
        val series = client.getJson(get(api.newBuilder().addPathSegment("series").addPathSegment(seriesKey).build()), KomgaSeries.serializer())
        return SeriesDetails(
            key = series.id,
            title = series.title(),
            author = series.writers(),
            artist = series.people("penciller", "artist", "inker"),
            description = (series.metadata.summary?.takeIf { it.isNotBlank() } ?: series.booksMetadata.summary)?.trim(),
            coverUrl = thumbnailUrl(series.id),
            status = statusOf(series.metadata.status),
            genres = (series.metadata.genres + series.metadata.tags).distinct(),
            webUrl = api.resolve("../../series/${series.id}")?.toString(),
        )
    }

    override suspend fun chapters(seriesKey: String): List<SourceChapter> {
        val url = api.newBuilder().addPathSegment("series").addPathSegment(seriesKey).addPathSegment("books")
            .addQueryParameter("unpaged", "true")
            .addQueryParameter("sort", "metadata.numberSort,asc")
            .build()
        val books = client.getJson(get(url), KomgaPage.serializer(KomgaBook.serializer())).content
        return books.map { book ->
            SourceChapter(
                key = book.id,
                name = book.metadata.title?.takeIf { it.isNotBlank() } ?: book.name,
                number = book.metadata.numberSort ?: book.number,
                volume = null,
                scanlator = null,
                uploadedAt = parseDate(book.metadata.releaseDate) ?: parseDate(book.created),
                pageCount = book.media.pagesCount.takeIf { it > 0 },
            )
        }
    }

    override suspend fun pages(seriesKey: String, chapterKey: String): List<SourcePage> {
        val bookUrl = api.newBuilder().addPathSegment("books").addPathSegment(chapterKey)
        val pages = client.getJson(get(bookUrl.addPathSegment("pages").build()), ListSerializer(KomgaBookPage.serializer()))
        return pages.sortedBy { it.number }.mapIndexed { index, page ->
            SourcePage(index, api.newBuilder().addPathSegment("books").addPathSegment(chapterKey)
                .addPathSegment("pages").addPathSegment(page.number.toString()).build().toString())
        }
    }

    override suspend fun fetchPage(page: SourcePage, destination: File, onProgress: (Long, Long) -> Unit) {
        client.download(get(page.url.toHttpUrl()), destination, onProgress)
    }

    private fun thumbnailUrl(seriesId: String) =
        api.newBuilder().addPathSegment("series").addPathSegment(seriesId).addPathSegment("thumbnail").build().toString()

    private fun get(url: HttpUrl) = Request.Builder().url(url).header("Accept", "application/json, image/*").get().build()

    private fun KomgaSeries.title() = metadata.title?.takeIf { it.isNotBlank() } ?: name

    private fun KomgaSeries.writers() = people("writer")

    private fun KomgaSeries.people(vararg roles: String): String? =
        booksMetadata.authors.filter { it.role.lowercase() in roles }.map { it.name }.distinct()
            .joinToString(", ").takeIf { it.isNotBlank() }

    companion object {
        private const val PAGE_SIZE = 30

        fun statusOf(value: String?) = when (value?.uppercase()) {
            "ONGOING" -> SeriesStatus.ONGOING
            "ENDED" -> SeriesStatus.COMPLETED
            "HIATUS" -> SeriesStatus.HIATUS
            "ABANDONED" -> SeriesStatus.CANCELLED
            else -> SeriesStatus.UNKNOWN
        }

        internal fun parseDate(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }
}
