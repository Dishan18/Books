package io.github.dishan18.books.discover

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.discover.catalog.CatalogItem
import io.github.dishan18.books.discover.catalog.CatalogListing
import io.github.dishan18.books.discover.catalog.GutenbergCatalog
import io.github.dishan18.books.discover.catalog.OpdsCatalog
import io.github.dishan18.books.manga.source.api.SourceException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

class BookCatalogsTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = routes[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(body).addHeader("Content-Type", "application/atom+xml").build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private val client = OkHttpClient()
    private fun base() = server.url("/").toString().trimEnd('/')

    @Test
    fun gutenbergListsOnlyBooksWithCovers() = runTest {
        routes["/ebooks/search.opds/"] = OpdsFixtures.gutenbergSearch
        val catalog = GutenbergCatalog("gutenberg", client, base())
        val page = catalog.search("dickens")

        val books = page.items.filterIsInstance<CatalogItem.BookItem>().map { it.book }
        assertEquals("subject and author links aren't books", listOf("98", "1400"), books.map { it.key })
        assertEquals("Charles Dickens", books.first().author)
        assertEquals("${base()}/cache/epub/98/pg98.cover.medium.jpg", books.first().coverUrl)
        assertTrue(page.nextUrl!!.contains("start_index=26"))
        assertEquals("dickens", requests.single().url.queryParameter("query"))
    }

    @Test
    fun gutenbergPopularAndNewestUseTheirSortOrders() = runTest {
        routes["/ebooks/search.opds/"] = OpdsFixtures.gutenbergSearch
        val catalog = GutenbergCatalog("gutenberg", client, base())
        catalog.browse(CatalogListing.POPULAR)
        catalog.browse(CatalogListing.LATEST)
        assertEquals(listOf("downloads", "release_date"), requests.map { it.url.queryParameter("sort_order") })
    }

    @Test
    fun gutenbergDetailsOfferImagesAndTextOnlyEditions() = runTest {
        routes["/ebooks/1342.opds"] = OpdsFixtures.gutenbergBook
        val catalog = GutenbergCatalog("gutenberg", client, base())
        val book = catalog.details(io.github.dishan18.books.discover.catalog.OnlineBook("gutenberg", "1342", "", null, null))

        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.author)
        assertEquals("\"Pride and Prejudice\" by Jane Austen is a novel published in 1813.", book.summary)
        assertEquals(listOf("Romance", "England -- Fiction", "Love stories"), book.subjects)
        assertEquals(listOf("With images", "Text only"), book.files.map { it.label })
        assertEquals(listOf(24864457L, 609138L), book.files.map { it.sizeBytes })
        assertTrue(book.files.all { it.format == BookFormat.EPUB })
        assertEquals("Public domain in the USA.", book.rights)
        assertTrue(book.complete)
    }

    @Test
    fun naturalNamesFlipLibraryStyleNames() {
        assertEquals("Jane Austen", GutenbergCatalog.naturalName("Austen, Jane"))
        assertEquals("Homer", GutenbergCatalog.naturalName("Homer"))
        assertEquals("Austen, Jane, 1775-1817", GutenbergCatalog.naturalName("Austen, Jane, 1775-1817"))
    }

    @Test
    fun opdsCatalogShowsFoldersAndBooksAndSearchesThroughOpenSearch() = runTest {
        routes["/opds"] = OpdsFixtures.calibreRoot
        routes["/opds/search.xml"] = OpdsFixtures.openSearch
        routes["/opds/search/hobbit"] = OpdsFixtures.calibreRoot
        val catalog = OpdsCatalog("opds-1", "Home", "${base()}/opds", client)

        val page = catalog.browse(CatalogListing.START)
        val folders = page.items.filterIsInstance<CatalogItem.Folder>()
        val books = page.items.filterIsInstance<CatalogItem.BookItem>().map { it.book }
        assertEquals(listOf("By Author"), folders.map { it.title })
        assertEquals(listOf("The Hobbit", "Store Only"), books.map { it.title })
        assertEquals(1, books[0].files.size)
        assertTrue("nothing free to download", books[1].files.isEmpty())

        assertTrue(catalog.supportsSearch())
        catalog.search("hobbit")
        assertEquals("/opds/search/hobbit", requests.last().url.encodedPath)
    }

    @Test
    fun anHtmlPageIsReportedAsNotACatalog() = runTest {
        routes["/login"] = "<html><body>Please sign in</body></html>"
        try {
            OpdsCatalog("opds-1", "Home", "${base()}/login", client).browse(CatalogListing.START)
            fail("expected an error")
        } catch (e: SourceException) {
            assertEquals("This address isn't an OPDS catalog", e.error.message)
        }
    }
}
