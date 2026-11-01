package io.github.dishan18.books.discover

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.data.CatalogInfo
import io.github.dishan18.books.data.Readium
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AddDownloadedBookTest {
    private lateinit var context: Context
    private lateinit var db: BooksDatabase
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).build()
        repository = BookRepository(context, db, Readium(context))
    }

    @After
    fun tearDown() = runBlocking {
        db.books().getAll().forEach { repository.delete(it) }
        db.close()
    }

    private val info = CatalogInfo("gutenberg", "1342", "Pride and Prejudice", "Jane Austen", listOf("Romance", "England -- Fiction"), "https://example.org/cover.jpg")

    @Test
    fun addsTheBookWithCatalogDetailsAndNeverTwice() = runBlocking {
        var coverRequests = 0
        val cover = ByteArrayOutputStream().also { Bitmap.createBitmap(30, 45, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

        val first = repository.addDownloaded(epub("Pride and Prejudice"), BookFormat.EPUB, info) { coverRequests++; cover }.getOrThrow()
        assertEquals("Pride and Prejudice", first.title)
        assertEquals("gutenberg", first.sourceId)
        assertEquals("1342", first.sourceKey)
        assertEquals(listOf("Romance", "England -- Fiction"), first.subjectList)
        assertEquals("Jane Austen", first.author)
        assertTrue("the EPUB has no cover, so the catalog's is used", first.hasCover)
        assertEquals(1, coverRequests)
        assertTrue(repository.fileOf(first).isFile)

        val again = epub("Pride and Prejudice")
        val second = repository.addDownloaded(again, BookFormat.EPUB, info) { cover }.getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals(1, db.books().getAll().size)
        assertFalse("the duplicate download is cleaned up", again.exists())
    }

    @Test
    fun aBrokenFileIsRejectedAndLeavesNothingBehind() = runBlocking {
        val junk = File(context.cacheDir, "junk.epub").apply { writeText("PK not really a zip") }
        val result = repository.addDownloaded(junk, BookFormat.EPUB, info) { null }
        assertTrue(result.isFailure)
        assertTrue(db.books().getAll().isEmpty())
    }

    /** A minimal valid EPUB 3 with one chapter and no cover. */
    private fun epub(title: String): File {
        val file = File(context.cacheDir, "test-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String, stored: Boolean = false) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip", stored = true)
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put(
                "content.opf",
                """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test</dc:identifier><dc:title>$title</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
            )
            put("nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">One</a></li></ol></nav></body></html>""")
            put("c1.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>It is a truth universally acknowledged.</p></body></html>""")
        }
        return file
    }
}
