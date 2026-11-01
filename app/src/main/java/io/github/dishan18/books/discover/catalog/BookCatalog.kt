package io.github.dishan18.books.discover.catalog

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.discover.opds.OpdsFeed
import io.github.dishan18.books.discover.opds.OpdsLink
import io.github.dishan18.books.discover.opds.OpdsParser
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.net.await
import io.github.dishan18.books.manga.source.net.requireSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.SAXException

/** A downloadable file of a book, in a format the app can read. */
data class BookFile(val url: String, val format: BookFormat, val label: String?, val sizeBytes: Long?)

/** A book as a catalog describes it. */
data class OnlineBook(
    val sourceId: String,
    /** Identifier inside the catalog, stable across visits. */
    val key: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val summary: String? = null,
    val subjects: List<String> = emptyList(),
    val language: String? = null,
    val rights: String? = null,
    val files: List<BookFile> = emptyList(),
    /** Where the full details live, when a listing only has a summary of the book. */
    val detailsUrl: String? = null,
    /** True once [files] and the rest are complete. */
    val complete: Boolean = false,
)

sealed interface CatalogItem {
    data class BookItem(val book: OnlineBook) : CatalogItem
    data class Folder(val title: String, val url: String, val subtitle: String?) : CatalogItem
}

data class CatalogPage(val title: String?, val items: List<CatalogItem>, val nextUrl: String?)

/** Ways into a catalog. Gutenberg has popular and newest lists; an OPDS catalog has its own start page. */
enum class CatalogListing(val label: String) { POPULAR("Popular"), LATEST("Newest"), START("Catalog") }

/** An online book catalog. Implementations only use documented, public (or the user's own) endpoints. */
interface BookCatalog {
    val id: String
    val name: String
    val listings: List<CatalogListing>

    suspend fun browse(listing: CatalogListing): CatalogPage

    /** Opens a folder or a next page. */
    suspend fun open(url: String): CatalogPage

    suspend fun search(query: String): CatalogPage

    /** Fills in files and details for a book from a listing. */
    suspend fun details(book: OnlineBook): OnlineBook
}

/** Formats the app reads, from OPDS acquisition links that are free to download. */
internal fun List<OpdsLink>.readableFiles(): List<BookFile> = filter { it.isFreeAcquisition }.mapNotNull { link ->
    val type = link.type.orEmpty().lowercase()
    val path = link.href.substringBefore('?').lowercase()
    val format = when {
        type.startsWith("application/epub+zip") || path.endsWith(".epub") -> BookFormat.EPUB
        type.startsWith("application/pdf") || path.endsWith(".pdf") -> BookFormat.PDF
        else -> null
    }
    format?.let { BookFile(link.href, it, link.title, link.length?.takeIf { size -> size > 0 }) }
}.distinctBy { it.url }

/** Fetches and parses an OPDS feed. */
internal suspend fun OkHttpClient.fetchFeed(url: String): OpdsFeed {
    val response = newCall(Request.Builder().url(url).header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.5").build())
        .await().requireSuccess()
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                OpdsParser.parse(it.body.byteStream(), it.request.url.toString())
            } catch (e: SAXException) {
                throw SourceException(SourceError.Other(NOT_A_CATALOG), e)
            } catch (e: OpdsParser.NotAFeedException) {
                throw SourceException(SourceError.Other(NOT_A_CATALOG), e)
            }
        }
    }
}

internal const val NOT_A_CATALOG = "This address isn't an OPDS catalog"

internal suspend fun OkHttpClient.fetchText(url: String): Pair<String, String> {
    val response = newCall(Request.Builder().url(url).build()).await().requireSuccess()
    return withContext(Dispatchers.IO) { response.use { it.body.string() to it.request.url.toString() } }
}
