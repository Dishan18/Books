package io.github.dishan18.books.office

import io.github.dishan18.books.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hostile and broken files must fail with a readable message, never hang, exhaust memory or reach outside. */
class OfficePackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg parts: Pair<String, ByteArray>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            parts.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private val rels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>""".toByteArray()

    @Test
    fun refusesDocumentTypeDeclarations() {
        val billionLaughs = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
            <workbook>&lol2;</workbook>""".toByteArray()
        val external = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><workbook>&xxe;</workbook>""".toByteArray()
        val utf16 = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE x []><workbook/>""".toByteArray(Charsets.UTF_16LE)
        listOf(billionLaughs, external, utf16).forEach { part ->
            val file = zip("_rels/.rels" to rels, "xl/workbook.xml" to part)
            OfficePackage(file).use { pkg ->
                val e = assertThrows(OfficeFileException::class.java) { pkg.xml("xl/workbook.xml") }
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun capsHowMuchAPartMayInflate() {
        // 64 MB of zeros compresses to a few kilobytes.
        val bomb = temp.newFile()
        ZipOutputStream(bomb.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("big.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(64) { out.write(chunk) }
            out.closeEntry()
        }
        assertTrue(bomb.length() < 1024 * 1024)
        OfficePackage(bomb).use { pkg ->
            assertThrows(OfficeFileException::class.java) { pkg.bytes("big.bin", maxBytes = 1024 * 1024) }
        }
    }

    @Test
    fun rejectsWhatIsntAnOfficeFile() {
        val notZip = temp.newFile().apply { writeText("%PDF-1.7 not a zip") }
        assertThrows(OfficeFileException::class.java) { OfficePackage(notZip) }

        // Old .xls/.ppt files and password-protected ones share this "compound file" signature.
        val compound = temp.newFile().apply {
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(512))
        }
        val e = assertThrows(OfficeFileException::class.java) { OfficeFiles.verify(compound, BookFormat.XLSX) }
        assertTrue("password" in e.message!!)

        // A presentation renamed .xlsx isn't taken for a spreadsheet.
        val deck = File(javaClass.getResource("/office/deck.pptx")!!.toURI())
        assertThrows(OfficeFileException::class.java) { OfficeFiles.verify(deck, BookFormat.XLSX) }
        OfficeFiles.verify(deck, BookFormat.PPTX)

        val epubLike = zip("mimetype" to "application/epub+zip".toByteArray())
        assertThrows(OfficeFileException::class.java) { OfficeFiles.verify(epubLike, BookFormat.PPTX) }
    }

    @Test
    fun damagedXmlFailsCleanly() {
        val file = zip("_rels/.rels" to rels, "xl/workbook.xml" to "<workbook><sheets>".toByteArray())
        OfficePackage(file).use { pkg ->
            assertThrows(OfficeFileException::class.java) { pkg.xml("xl/workbook.xml") }
        }
    }

    @Test
    fun resolvesLinksBetweenParts() {
        assertEquals("ppt/media/image1.png", OfficePackage.resolvePart("ppt/slides/slide1.xml", "../media/image1.png"))
        assertEquals("ppt/slides/slide2.xml", OfficePackage.resolvePart("ppt/presentation.xml", "slides/slide2.xml"))
        assertEquals("xl/workbook.xml", OfficePackage.resolvePart("", "/xl/workbook.xml"))
        // Links can't climb out of the file.
        assertEquals("etc/passwd", OfficePackage.resolvePart("ppt/slides/slide1.xml", "../../../../etc/passwd"))
        assertEquals("ppt/media/my image é.png", OfficePackage.resolvePart("ppt/slides/s.xml", "../media/my%20image%20%C3%A9.png"))
        assertEquals("ppt/slides/_rels/slide1.xml.rels", OfficePackage.relsPathOf("ppt/slides/slide1.xml"))
        assertEquals("_rels/.rels", OfficePackage.relsPathOf(""))
    }
}
