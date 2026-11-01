package io.github.dishan18.books.discover

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.discover.catalog.readableFiles
import io.github.dishan18.books.discover.opds.OpdsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpdsParserTest {

    @Test
    fun parsesGutenbergListingWithResolvedLinks() {
        val feed = OpdsParser.parse(OpdsFixtures.gutenbergSearch, "https://www.gutenberg.org/ebooks/search.opds/?query=dickens")
        assertEquals("Books: dickens", feed.title)
        assertEquals(3, feed.entries.size)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=dickens&start_index=26", feed.nextUrl)
        val tale = feed.entries[1]
        assertEquals("A Tale of Two Cities", tale.title)
        assertEquals(listOf("Charles Dickens"), tale.contentParagraphs)
        assertEquals("https://www.gutenberg.org/ebooks/98.opds", tale.navigationLink?.href)
        assertNull("data: thumbnails aren't covers", tale.coverUrl)
    }

    @Test
    fun parsesXhtmlContentCategoriesAndAcquisitions() {
        val feed = OpdsParser.parse(OpdsFixtures.gutenbergBook, "https://www.gutenberg.org/ebooks/1342.opds")
        val entry = feed.entries.first()
        assertEquals(listOf("Austen, Jane"), entry.authors)
        assertTrue(entry.contentParagraphs.any { it.startsWith("Summary: \"Pride and Prejudice\"") })
        assertEquals("en", entry.language)
        assertEquals("Public domain in the USA.", entry.rights)
        assertEquals(listOf("England -- Fiction", "Love stories", "Text"), entry.categories.map { it.first })
        val files = entry.acquisitions.readableFiles()
        assertEquals("the Kindle file isn't readable here", 1, files.size)
        assertEquals(BookFormat.EPUB, files.single().format)
        assertEquals(609138L, files.single().sizeBytes)
    }

    @Test
    fun onlyFreeEpubAndPdfLinksAreOffered() {
        val feed = OpdsParser.parse(OpdsFixtures.calibreRoot, "http://192.168.1.20:8083/opds")
        val hobbit = feed.entries[1]
        assertEquals("In a hole in the ground & more.", hobbit.summary)
        assertEquals("http://192.168.1.20:8083/thumb/1", hobbit.coverUrl)
        val files = hobbit.acquisitions.readableFiles()
        assertEquals(listOf("http://192.168.1.20:8083/get/epub/1"), files.map { it.url })
        assertTrue("borrowing and unreadable formats aren't offered", feed.entries[2].acquisitions.readableFiles().isEmpty())
        assertEquals("http://192.168.1.20:8083/opds/authors", feed.entries[0].navigationLink?.href)
        assertEquals("http://192.168.1.20:8083/opds?offset=2", feed.nextUrl)
        assertEquals("http://192.168.1.20:8083/opds/search.xml", feed.search?.href)
    }

    @Test
    fun readsOpenSearchTemplatesAndFillsThem() {
        val template = OpdsParser.parseOpenSearchTemplate(OpdsFixtures.openSearch, "http://192.168.1.20:8083/opds/search.xml")
        assertEquals("http://192.168.1.20:8083/opds/search/{searchTerms}?page={startPage?}", template)
        assertEquals("http://192.168.1.20:8083/opds/search/war%20%26%20peace?page=", OpdsParser.fillTemplate(template!!, "war & peace"))
    }

    @Test
    fun refusesDocumentTypeDeclarations() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE feed [ <!ENTITY secret SYSTEM "file:///etc/hosts"> ]>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>&secret;</title></feed>
        """.trimIndent()
        try {
            val feed = OpdsParser.parse(hostile, "https://example.org/")
            assertFalse("external entities must never be expanded", feed.title.orEmpty().contains("localhost"))
        } catch (expected: Exception) {
            // Refusing the document outright is the usual outcome.
        }
    }

    @Test
    fun rejectsDocumentsThatArentFeeds() {
        try {
            OpdsParser.parse("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Sign in</body></html>", "https://example.org/")
            fail("an HTML page isn't a catalog")
        } catch (expected: OpdsParser.NotAFeedException) {
        }
    }
}
