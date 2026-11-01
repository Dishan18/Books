package io.github.dishan18.books.manga.source.mangadex

import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.source.MangaDexSettings
import io.github.dishan18.books.manga.source.SourceType
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SeriesDetails
import io.github.dishan18.books.manga.source.api.SeriesPage
import io.github.dishan18.books.manga.source.api.SourceChapter
import io.github.dishan18.books.manga.source.api.SourcePage
import io.github.dishan18.books.manga.source.api.SourceSeries
import io.github.dishan18.books.manga.source.net.RateLimitInterceptor
import io.github.dishan18.books.manga.source.net.RateLimiter
import io.github.dishan18.books.manga.source.net.SourceJson
import io.github.dishan18.books.manga.source.net.await
import io.github.dishan18.books.manga.source.net.download
import io.github.dishan18.books.manga.source.net.getJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.OffsetDateTime

/**
 * MangaDex through its official public API v5. No account is needed for reading. Rate limits are
 * enforced by the client passed in (see [MangaDexSource.rateLimitRules]).
 */
class MangaDexSource(
    private val client: OkHttpClient,
    private val settings: MangaDexSettings,
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
    private val uploadsBase: String = UPLOADS_BASE,
    private val reportUrl: String = REPORT_URL,
) : MangaSource {
    override val id = ID
    override val name = "MangaDex"
    override val type = SourceType.MANGADEX
    override val isOnline = true
    override val supportsLatest = true

    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun browse(listing: Listing, page: Int): SeriesPage {
        val order = when (listing) {
            Listing.POPULAR -> "followedCount"
            Listing.LATEST -> "latestUploadedChapter"
        }
        return searchManga(page) { addQueryParameter("order[$order]", "desc") }
    }

    override suspend fun search(query: String, page: Int): SeriesPage =
        searchManga(page) {
            addQueryParameter("title", query.trim())
            addQueryParameter("order[relevance]", "desc")
        }

    private suspend fun searchManga(page: Int, configure: HttpUrl.Builder.() -> Unit): SeriesPage {
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val url = apiBase.newBuilder().addPathSegment("manga")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("hasAvailableChapters", "true")
            .apply {
                settings.contentRatings.forEach { addQueryParameter("contentRating[]", it) }
                settings.languages.forEach { addQueryParameter("availableTranslatedLanguage[]", it) }
                configure()
            }
            .build()
        val response = client.getJson(get(url), MdListResponse.serializer(MdManga.serializer()))
        // MangaDex caps offset + limit at 10,000.
        val hasNext = offset + response.data.size < minOf(response.total, MAX_RESULTS)
        return SeriesPage(response.data.map { it.toSourceSeries() }, hasNext)
    }

    override suspend fun details(seriesKey: String): SeriesDetails {
        val url = apiBase.newBuilder().addPathSegment("manga").addPathSegment(seriesKey)
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
        val manga = client.getJson(get(url), MdEntityResponse.serializer(MdManga.serializer())).data
        val attrs = manga.attributes
        val genres = attrs.tags
            .filter { it.attributes.group == "genre" || it.attributes.group == "theme" }
            .mapNotNull { it.attributes.name.localized(listOf("en")) }
        return SeriesDetails(
            key = manga.id,
            title = manga.title(),
            author = manga.people("author"),
            artist = manga.people("artist"),
            description = attrs.description.localized(settings.languages)?.trim(),
            coverUrl = manga.coverUrl(),
            status = statusOf(attrs.status),
            genres = genres,
            webUrl = "https://mangadex.org/title/${manga.id}",
        )
    }

    override suspend fun chapters(seriesKey: String): List<SourceChapter> {
        val all = mutableListOf<MdChapter>()
        var offset = 0
        do {
            val url = apiBase.newBuilder()
                .addPathSegment("manga").addPathSegment(seriesKey).addPathSegment("feed")
                .addQueryParameter("limit", FEED_PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("order[volume]", "asc")
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("includeEmptyPages", "0")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addQueryParameter("includeExternalUrl", "0")
                .apply {
                    settings.languages.forEach { addQueryParameter("translatedLanguage[]", it) }
                    MangaDexSettings.ALL_CONTENT_RATINGS.forEach { addQueryParameter("contentRating[]", it) }
                }
                .build()
            val page = client.getJson(get(url), MdListResponse.serializer(MdChapter.serializer()))
            all += page.data
            offset += page.data.size
        } while (page.data.isNotEmpty() && offset < page.total)

        val readable = all.filter { !it.attributes.isUnavailable && it.attributes.externalUrl == null }
        return orderChapters(readable).map { it.toSourceChapter() }
    }

    override suspend fun pages(seriesKey: String, chapterKey: String): List<SourcePage> {
        val url = apiBase.newBuilder().addPathSegment("at-home").addPathSegment("server").addPathSegment(chapterKey).build()
        val atHome = client.getJson(get(url), MdAtHome.serializer())
        val (folder, files) = if (settings.dataSaver && atHome.chapter.dataSaver.isNotEmpty()) {
            "data-saver" to atHome.chapter.dataSaver
        } else {
            "data" to atHome.chapter.data
        }
        return files.mapIndexed { index, file ->
            SourcePage(index, "${atHome.baseUrl}/$folder/${atHome.chapter.hash}/$file")
        }
    }

    override suspend fun fetchPage(page: SourcePage, destination: File, onProgress: (Long, Long) -> Unit) {
        val started = System.currentTimeMillis()
        var bytes = 0L
        try {
            val response = client.download(get(page.url.toHttpUrl()), destination) { read, total ->
                bytes = read
                onProgress(read, total)
            }
            report(page.url, success = true, cached = response.header("X-Cache")?.startsWith("HIT") == true, bytes, started)
        } catch (e: Exception) {
            report(page.url, success = false, cached = false, bytes, started)
            throw e
        }
    }

    /**
     * MangaDex asks clients to report MangaDex@Home image fetches so it can keep its volunteer CDN
     * healthy. Only the image URL, size, timing and cache status are sent; never anything about you.
     */
    private fun report(url: String, success: Boolean, cached: Boolean, bytes: Long, started: Long) {
        if (url.startsWith(uploadsBase)) return
        val body = SourceJson.encodeToString(
            MdAtHomeReport.serializer(),
            MdAtHomeReport(url, success, cached, bytes, System.currentTimeMillis() - started),
        ).toRequestBody("application/json".toMediaType())
        reportScope.launch {
            runCatching { client.newCall(Request.Builder().url(reportUrl).post(body).build()).await().close() }
        }
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).get().build()

    private fun MdManga.title(): String =
        attributes.title.localized(settings.languages)
            ?: attributes.altTitles.firstNotNullOfOrNull { it.localized(settings.languages) }
            ?: "Untitled"

    private fun MdManga.people(type: String): String? =
        relationships.filter { it.type == type }
            .mapNotNull { it.attributes?.string("name") }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

    private fun MdManga.coverUrl(): String? {
        val fileName = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.string("fileName") ?: return null
        return "$uploadsBase/covers/$id/$fileName.512.jpg"
    }

    private fun MdManga.toSourceSeries() = SourceSeries(
        key = id,
        title = title(),
        coverUrl = coverUrl(),
        author = people("author"),
        status = statusOf(attributes.status),
    )

    companion object {
        const val ID = "mangadex"
        const val API_BASE = "https://api.mangadex.org/"
        const val UPLOADS_BASE = "https://uploads.mangadex.org"
        const val REPORT_URL = "https://api.mangadex.network/report"
        private const val PAGE_SIZE = 30
        private const val FEED_PAGE_SIZE = 500
        private const val MAX_RESULTS = 10_000

        /**
         * Stays under MangaDex's documented limits: about 5 requests/second per IP for the API and
         * 40 requests/minute for the at-home server endpoint.
         */
        fun rateLimits() = RateLimitInterceptor(
            listOf(
                { r: Request -> r.url.encodedPath.startsWith("/at-home/") } to RateLimiter(40, 60_000),
                { r: Request -> r.url.host == "api.mangadex.org" } to RateLimiter(4, 1_000),
            ),
        )

        fun statusOf(value: String?) = when (value) {
            "ongoing" -> SeriesStatus.ONGOING
            "completed" -> SeriesStatus.COMPLETED
            "hiatus" -> SeriesStatus.HIATUS
            "cancelled" -> SeriesStatus.CANCELLED
            else -> SeriesStatus.UNKNOWN
        }
    }
}

/**
 * Reading order: by chapter number, then volume, then publish date, keeping the API's order for
 * ties. Chapters without a number (oneshots, extras) keep their relative position at the end.
 */
internal fun orderChapters(chapters: List<MdChapter>): List<MdChapter> =
    chapters.withIndex().sortedWith(
        compareBy<IndexedValue<MdChapter>>(
            { it.value.attributes.chapter?.toFloatOrNull() ?: Float.MAX_VALUE },
            { it.value.attributes.volume?.toFloatOrNull() ?: Float.MAX_VALUE },
            { it.value.attributes.publishAt.orEmpty() },
            { it.index },
        ),
    ).map { it.value }

internal fun MdChapter.toSourceChapter(): SourceChapter {
    val attrs = attributes
    val label = buildString {
        attrs.volume?.takeIf { it.isNotBlank() }?.let { append("Vol. ").append(it).append(' ') }
        attrs.chapter?.takeIf { it.isNotBlank() }?.let { append("Ch. ").append(it) }
    }.trim()
    val title = attrs.title?.trim().orEmpty()
    val name = when {
        label.isNotEmpty() && title.isNotEmpty() -> "$label - $title"
        label.isNotEmpty() -> label
        title.isNotEmpty() -> title
        else -> "Oneshot"
    }
    val group = relationships.filter { it.type == "scanlation_group" }
        .mapNotNull { it.attributes?.string("name") }
        .joinToString(" & ")
        .takeIf { it.isNotBlank() }
    return SourceChapter(
        key = id,
        name = name,
        number = attrs.chapter?.toFloatOrNull(),
        volume = attrs.volume,
        scanlator = group,
        uploadedAt = (attrs.publishAt ?: attrs.readableAt)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
        pageCount = attrs.pages.takeIf { it > 0 },
    )
}
