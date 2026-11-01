package io.github.dishan18.books.discover.catalog

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.discover.opds.OpdsEntry
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.Locale

/**
 * Project Gutenberg, through its official OPDS catalog: 75,000+ public-domain books, free to
 * download. Listings name each book; the book's own feed has its files and details.
 */
class GutenbergCatalog(
    override val id: String,
    private val client: OkHttpClient,
    private val base: String = "https://www.gutenberg.org",
) : BookCatalog {
    override val name = "Project Gutenberg"
    override val listings = listOf(CatalogListing.POPULAR, CatalogListing.LATEST)

    override suspend fun browse(listing: CatalogListing): CatalogPage = open(
        when (listing) {
            CatalogListing.LATEST -> "$base/ebooks/search.opds/?sort_order=release_date"
            else -> "$base/ebooks/search.opds/?sort_order=downloads"
        },
    )

    override suspend fun search(query: String): CatalogPage =
        open("$base/ebooks/search.opds/?query=${URLEncoder.encode(query.trim(), "UTF-8")}")

    override suspend fun open(url: String): CatalogPage {
        val feed = client.fetchFeed(url)
        // Listings mix books with "Authors", "Subjects" and sorting links; keep the books.
        val books = feed.entries.mapNotNull { entry ->
            val bookId = bookIdOf(entry) ?: return@mapNotNull null
            CatalogItem.BookItem(
                OnlineBook(
                    sourceId = id,
                    key = bookId,
                    title = entry.title,
                    author = entry.contentParagraphs.firstOrNull() ?: entry.authors.firstOrNull()?.let(::naturalName),
                    coverUrl = coverUrl(bookId),
                    detailsUrl = "$base/ebooks/$bookId.opds",
                ),
            )
        }
        return CatalogPage(feed.title, books, feed.nextUrl)
    }

    override suspend fun details(book: OnlineBook): OnlineBook {
        if (book.complete) return book
        val feed = client.fetchFeed(book.detailsUrl ?: "$base/ebooks/${book.key}.opds")
        val entries = feed.entries.filter { it.acquisitions.isNotEmpty() }
        val main = entries.firstOrNull() ?: feed.entries.firstOrNull() ?: return book.copy(complete = true)
        val files = entries.flatMap { it.acquisitions.readableFiles() }
        val bookshelves = main.links
            .filter { it.rel == "related" && it.href.contains("/bookshelf/") }
            .mapNotNull { it.title?.removePrefix("In ")?.removePrefix("Category: ")?.trimEnd('…', '.', ' ') }
            .filter { it.isNotBlank() && !it.contains("Best Books Ever") }
        val subjects = main.categories.filter { (_, scheme) -> scheme?.endsWith("LCSH") == true }.map { it.first }
        return book.copy(
            title = main.title,
            author = main.authors.map(::naturalName).joinToString(", ").ifBlank { book.author.orEmpty() }.ifBlank { null },
            coverUrl = main.coverUrl ?: book.coverUrl,
            summary = labelled(main, "Summary"),
            subjects = (bookshelves + subjects).distinct(),
            language = main.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } },
            rights = main.rights,
            files = preferred(files),
            complete = true,
        )
    }

    private fun bookIdOf(entry: OpdsEntry): String? {
        val candidates = listOfNotNull(entry.id) + entry.links.map { it.href }
        return candidates.firstNotNullOfOrNull { BOOK_FEED.find(it)?.groupValues?.get(1) }
    }

    private fun coverUrl(bookId: String) = "$base/cache/epub/$bookId/pg$bookId.cover.medium.jpg"

    /** A "Label: value" paragraph of Gutenberg's details, such as the summary. */
    private fun labelled(entry: OpdsEntry, label: String): String? =
        entry.contentParagraphs.firstOrNull { it.startsWith("$label:") }?.removePrefix("$label:")?.trim()?.ifBlank { null }

    /** EPUB3 with images and the small text-only EPUB cover every case; the older duplicates add nothing. */
    private fun preferred(files: List<BookFile>): List<BookFile> {
        val epub3 = files.firstOrNull { it.url.contains(".epub3.images") }
        val textOnly = files.firstOrNull { it.url.contains(".epub.noimages") }
        val picked = listOfNotNull(
            epub3?.copy(label = "With images"),
            textOnly?.copy(label = "Text only"),
        )
        return picked.ifEmpty { files.filter { it.format == BookFormat.EPUB }.ifEmpty { files } }
    }

    companion object {
        const val DEFAULT_ID = "gutenberg"
        private val BOOK_FEED = Regex("/ebooks/(\\d+)\\.opds")

        /** "Austen, Jane" reads as "Jane Austen". */
        fun naturalName(name: String): String {
            val parts = name.split(",").map { it.trim() }
            return if (parts.size == 2 && parts.none { it.isEmpty() || it.any(Char::isDigit) }) "${parts[1]} ${parts[0]}" else name
        }
    }
}
