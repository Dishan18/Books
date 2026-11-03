package io.github.dishan18.books.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.data.Readium
import io.github.dishan18.books.office.sheet.Workbook
import io.github.dishan18.books.office.slides.PresentationReader
import io.github.dishan18.books.office.slides.SlideDocument
import io.github.dishan18.books.office.slides.SlideRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * The parts that only Android can check: its own XML parser against hostile files, drawing slides,
 * covers, and importing through the library. Files come from src/test/resources/office.
 */
@RunWith(AndroidJUnit4::class)
class OfficeOnDeviceTest {
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

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @Test
    fun androidsParserRefusesEntityTricks() {
        val file = File(context.cacheDir, "evil.xlsx")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("_rels/.rels"))
            out.write(
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
                    .toByteArray(),
            )
            out.putNextEntry(ZipEntry("xl/workbook.xml"))
            out.write("""<?xml version="1.0"?><!DOCTYPE a [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;">]><workbook>&b;</workbook>""".toByteArray())
        }
        OfficePackage(file).use { pkg -> assertThrows(OfficeFileException::class.java) { pkg.xml("xl/workbook.xml") } }
        assertThrows(OfficeFileException::class.java) { Workbook.open(file) }
    }

    @Test
    fun readsSheetsWithAndroidsParser() {
        Workbook.open(fixture("sheet.xlsx"), Locale.US).use { book ->
            val sheet = book.readSheet(0)
            assertEquals("1,234.50", sheet.text(1, 1))
            assertEquals("15-Mar-24", sheet.text(1, 2))
            assertEquals(1, sheet.frozenRows)
            // Stopping early at the cell limit works through Android's parser too.
            val partial = book.readSheet(0, maxCells = 3)
            assertTrue(partial.truncated)
            assertEquals(3, partial.rows.values.sumOf { it.cols.size })
        }
        Workbook.open(fixture("shared.xlsx"), Locale.US).use { book ->
            assertEquals("Rich text", book.readSheet(0).text(0, 1))
        }
    }

    @Test
    fun drawsSlides() = runBlocking {
        val document = SlideDocument.open(fixture("deck.pptx"))
        try {
            assertEquals(3, document.pageCount)
            assertEquals(4f / 3f, document.aspectRatio(0), 0.001f)
            val bitmap = document.render(2, 800)!!
            assertEquals(800, bitmap.width)
            assertEquals(600, bitmap.height)
            // The red box (0.5 to 3.5 inches across, 0.5 to 1.5 down, at 80 px an inch) and the white slide around it.
            assertClose(0xFFFF0000.toInt(), bitmap.getPixel(60, 60))
            assertClose(0xFFFFFFFF.toInt(), bitmap.getPixel(780, 590))
            // The blue picture, 0.5 to 2.5 inches across and 2 to 3.5 down.
            assertClose(0xFF0080FF.toInt(), bitmap.getPixel(120, 220))

            // A zoomed-in region: the slide's top-left at (-400, -100), drawn 4000 px wide; outside stays clear.
            val region = document.renderRegion(2, 400, 300, -400f, -100f, 4000f)!!
            assertClose(0xFFFF0000.toInt(), region.getPixel(10, 150))
            val outside = document.renderRegion(2, 200, 200, 100f, 100f, 50f)!!
            assertEquals(0, outside.getPixel(199, 199) ushr 24)

            // Every slide draws at every zoom from a thumbnail to far past the screen without failing.
            for (index in 0 until document.pageCount) {
                for (width in listOf(1, 16, 360, 1080, 2400)) assertNotNull(document.render(index, width))
                for (pageWidth in listOf(10f, 1080f, 5400f, 20_000f)) assertNotNull(document.renderRegion(index, 1080, 2000, -pageWidth / 3, -pageWidth / 4, pageWidth))
            }
        } finally {
            document.close()
        }
    }

    @Test
    fun titleTextIsDrawn() {
        OfficePackage(fixture("deck.pptx")).use { pkg ->
            val deck = PresentationReader(pkg).read()
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            SlideRenderer(deck) { _, _, _ -> null }.draw(Canvas(bitmap), 0, 800 / deck.width)
            // The centred title band holds dark text pixels; the band above it is plain white.
            val dark = (0 until 800).count { x -> (220 until 290).any { y -> bitmap.getPixel(x, y) and 0xFF < 80 } }
            assertTrue("title text should be drawn", dark > 50)
            assertTrue((0 until 800).all { x -> bitmap.getPixel(x, 20) == 0xFFFFFFFF.toInt() })
        }
    }

    @Test
    fun importsFromOpenWithAndDrawsCovers() = runBlocking {
        val sheet = repository.import(Uri.fromFile(fixture("shared.xlsx"))).getOrThrow()
        assertEquals(BookFormat.XLSX, sheet.format)
        assertEquals("Quarterly figures", sheet.title)
        assertEquals("Test Author", sheet.author)
        assertTrue(sheet.hasCover)
        assertTrue(repository.coverOf(sheet).length() > 0)

        val deck = repository.import(Uri.fromFile(fixture("deck.pptx"))).getOrThrow()
        assertEquals(BookFormat.PPTX, deck.format)
        assertEquals("deck", deck.title) // no title in its properties: the file name
        assertTrue(deck.hasCover)
        assertEquals(2, db.books().getAll().size)

        // A presentation renamed .xlsx is refused, and nothing is left behind.
        val renamed = File(context.cacheDir, "renamed.xlsx").also { fixture("deck.pptx").copyTo(it, overwrite = true) }
        val result = repository.import(Uri.fromFile(renamed))
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is OfficeFileException)
        assertEquals(2, db.books().getAll().size)
        assertEquals(2, File(context.filesDir, "books").listFiles()!!.count { it.name.startsWith(sheet.id) || it.name.startsWith(deck.id) })
    }

    private fun assertClose(expected: Int, actual: Int) {
        val close = (0..2).all { shift -> abs((expected shr (shift * 8) and 0xFF) - (actual shr (shift * 8) and 0xFF)) <= 12 }
        assertTrue("expected #%08X, was #%08X".format(expected, actual), close)
    }
}
