package io.github.dishan18.books.discover.catalog

import io.github.dishan18.books.discover.opds.OpdsEntry
import io.github.dishan18.books.discover.opds.OpdsFeed
import io.github.dishan18.books.discover.opds.OpdsParser
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Any OPDS 1.x catalog: a Calibre, Kavita or Komga server at home, or a library the user has an
 * account with. Only free acquisition links are offered; buy, borrow and subscription links aren't.
 */
class OpdsCatalog(
    override val id: String,
    override val name: String,
    private val rootUrl: String,
    private val client: OkHttpClient,
) : BookCatalog {
    override val listings = listOf(CatalogListing.START)

    private val searchLock = Mutex()
    private var searchTemplate: String? = null
    private var searchChecked = false

    override suspend fun browse(listing: CatalogListing): CatalogPage = open(rootUrl)

    override suspend fun open(url: String): CatalogPage = page(client.fetchFeed(url))

    override suspend fun search(query: String): CatalogPage {
        val template = searchTemplate() ?: throw SourceException(SourceError.Other("This catalog doesn't support search"))
        return open(OpdsParser.fillTemplate(template, query.trim()))
    }

    /** Whether [search] can work; looks the catalog's search link up once. */
    suspend fun supportsSearch(): Boolean = runCatching { searchTemplate() != null }.getOrDefault(false)

    override suspend fun details(book: OnlineBook): OnlineBook {
        if (book.complete || book.detailsUrl == null) return book.copy(complete = true)
        val entry = client.fetchFeed(book.detailsUrl).entries.firstOrNull() ?: return book.copy(complete = true)
        return toBook(entry).copy(key = book.key, complete = true)
    }

    private suspend fun searchTemplate(): String? = searchLock.withLock {
        if (searchChecked) return searchTemplate
        val link = client.fetchFeed(rootUrl).search
        searchTemplate = when {
            link == null -> null
            link.href.contains("{searchTerms}") -> link.href
            else -> {
                val (xml, url) = client.fetchText(link.href)
                runCatching { OpdsParser.parseOpenSearchTemplate(xml, url) }.getOrNull()
            }
        }
        searchChecked = true
        searchTemplate
    }

    private fun page(feed: OpdsFeed): CatalogPage {
        val items = feed.entries.mapNotNull { entry ->
            when {
                entry.acquisitions.isNotEmpty() -> CatalogItem.BookItem(toBook(entry))
                entry.navigationLink != null -> CatalogItem.Folder(entry.title, entry.navigationLink!!.href, entry.summary?.take(140))
                entry.links.any { it.isFullEntry } -> CatalogItem.BookItem(toBook(entry))
                else -> null
            }
        }
        return CatalogPage(feed.title, items, feed.nextUrl)
    }

    private fun toBook(entry: OpdsEntry): OnlineBook {
        val files = entry.acquisitions.readableFiles()
        val fullEntry = entry.links.firstOrNull { it.isFullEntry }?.href
        return OnlineBook(
            sourceId = id,
            key = entry.id ?: files.firstOrNull()?.url ?: fullEntry ?: entry.title,
            title = entry.title,
            author = entry.authors.joinToString(", ").ifBlank { null },
            coverUrl = entry.coverUrl,
            summary = entry.summary,
            subjects = entry.categories.map { it.first }.distinct(),
            language = entry.language,
            rights = entry.rights,
            files = files,
            detailsUrl = fullEntry,
            complete = fullEntry == null,
        )
    }
}
