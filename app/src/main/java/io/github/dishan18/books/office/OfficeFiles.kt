package io.github.dishan18.books.office

import io.github.dishan18.books.data.BookFormat
import java.io.File

/** Recognizes Excel and PowerPoint files and reads their title and author. */
object OfficeFiles {

    /** MIME types Books accepts from "Open with", with the format each one is. Lowercase: look up `type.lowercase()`. */
    val mimeTypes = mapOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to BookFormat.XLSX,
        "application/vnd.ms-excel.sheet.macroenabled.12" to BookFormat.XLSX,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to BookFormat.PPTX,
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to BookFormat.PPTX,
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" to BookFormat.PPTX,
    )

    val extensions = mapOf(
        "xlsx" to BookFormat.XLSX,
        "xlsm" to BookFormat.XLSX,
        "pptx" to BookFormat.PPTX,
        "ppsx" to BookFormat.PPTX,
        "pptm" to BookFormat.PPTX,
    )

    /** Old binary Office files and password-protected ones are both "compound files" with this signature. */
    private val COMPOUND_FILE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    /**
     * Checks that [file] really is a [format] file (not, say, a renamed EPUB or an old .xls) and
     * throws [OfficeFileException] with a reason to show otherwise.
     */
    fun verify(file: File, format: BookFormat) {
        val head = ByteArray(COMPOUND_FILE.size)
        val read = file.inputStream().use { it.read(head) }
        if (read == head.size && head.contentEquals(COMPOUND_FILE)) {
            throw OfficeFileException("This file is password protected or in an old Office format, which Books can't open.")
        }
        val actual = OfficePackage(file).use(::formatOf)
        if (actual != format) {
            throw OfficeFileException(
                when (format) {
                    BookFormat.XLSX -> "This isn't an Excel file Books can open."
                    else -> "This isn't a PowerPoint file Books can open."
                },
            )
        }
    }

    /** What the package's main part is: a workbook, a presentation, or neither. */
    fun formatOf(pkg: OfficePackage): BookFormat? {
        val main = pkg.mainPart() ?: return null
        if (!pkg.has(main)) return null
        val contentType = contentTypeOf(pkg, main)
        return when {
            contentType != null && "spreadsheetml" in contentType -> BookFormat.XLSX
            contentType != null && "presentationml" in contentType -> BookFormat.PPTX
            main.endsWith("workbook.xml") -> BookFormat.XLSX
            main.endsWith("presentation.xml") -> BookFormat.PPTX
            else -> null
        }
    }

    private fun contentTypeOf(pkg: OfficePackage, part: String): String? {
        if (!pkg.has("[Content_Types].xml")) return null
        val types = pkg.xml("[Content_Types].xml")
        val name = "/$part"
        return types.children("Override").firstOrNull { it.attr("PartName").equals(name, ignoreCase = true) }?.attr("ContentType")
    }

    /** Title and author from the document's properties, when it has them. */
    fun properties(pkg: OfficePackage): Pair<String?, String?> {
        val core = pkg.relationships("").firstOrNull { it.isType("core-properties") && !it.external }?.target
            ?: "docProps/core.xml".takeIf(pkg::has)
            ?: return null to null
        if (!pkg.has(core)) return null to null
        val root = runCatching { pkg.xml(core) }.getOrNull() ?: return null to null
        fun value(name: String) = root.child(name)?.text?.trim()?.takeIf { it.isNotEmpty() }
        return value("title") to value("creator")
    }
}
