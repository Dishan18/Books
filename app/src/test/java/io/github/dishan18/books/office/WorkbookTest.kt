package io.github.dishan18.books.office

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.office.sheet.CellKind
import io.github.dishan18.books.office.sheet.CellRange
import io.github.dishan18.books.office.sheet.HAlign
import io.github.dishan18.books.office.sheet.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Reads files written by openpyxl and in Excel's own layout (see resources/office/make_fixtures.py). */
class WorkbookTest {

    private fun fixture(name: String) = File(javaClass.getResource("/office/$name")!!.toURI())

    @Test
    fun readsValuesAsExcelShowsThem() {
        Workbook.open(fixture("sheet.xlsx"), Locale.US).use { book ->
            assertEquals(listOf("Budget", "Second"), book.sheets.map { it.name }) // the hidden sheet stays hidden
            val sheet = book.readSheet(0)
            assertEquals("Item", sheet.text(0, 0))
            assertEquals("1,234.50", sheet.text(1, 1))
            assertEquals("15-Mar-24", sheet.text(1, 2))
            assertEquals("25.6%", sheet.text(1, 3))
            assertEquals("($42.00)", sheet.text(2, 1))
            assertEquals("2024-03-15 13:45:30", sheet.text(2, 2))
            assertEquals("TRUE", sheet.text(2, 3))
            // Saved without a result: the formula is shown rather than nothing.
            assertEquals("=SUM(B2:B3)", sheet.text(3, 1))
            assertEquals("1.23457E+13", sheet.text(6, 4))
            assertEquals("0.3", sheet.text(7, 4))
            assertNull(sheet.text(0, 10))

            val row = sheet.rows.getValue(1)
            assertEquals(CellKind.NUMBER, row.kinds[row.indexOf(1)])
            assertEquals(CellKind.TEXT, row.kinds[row.indexOf(0)])
            val third = sheet.rows.getValue(2)
            assertEquals(CellKind.BOOLEAN, third.kinds[third.indexOf(3)])
        }
    }

    @Test
    fun readsLayoutAndStyles() {
        Workbook.open(fixture("sheet.xlsx"), Locale.US).use { book ->
            val sheet = book.readSheet(0)
            val header = sheet.rows.getValue(0)
            val style = sheet.style(header.styles[header.indexOf(0)])
            assertTrue(style.bold)
            assertEquals(0xFFFFFF00.toInt(), style.fill)
            val food = sheet.rows.getValue(2)
            assertEquals(0xFFFF0000.toInt(), sheet.style(food.styles[food.indexOf(1)]).textColor)
            val wrapped = sheet.rows.getValue(5)
            val wrapStyle = sheet.style(wrapped.styles[wrapped.indexOf(0)])
            assertTrue(wrapStyle.wrap)
            assertEquals(HAlign.CENTER, wrapStyle.horizontal)

            assertEquals(listOf(CellRange(9, 0, 9, 2)), sheet.merges)
            assertEquals(140f, sheet.colWidth(0)) // width 20 characters
            assertEquals(64f, sheet.colWidth(1))
            assertEquals(0f, sheet.rowHeight(8)) // hidden row 9
            assertEquals(20f, sheet.rowHeight(0))
            assertEquals(1, sheet.frozenRows)
            assertEquals(1, sheet.frozenCols)
            assertTrue(sheet.showGrid)
            assertEquals(10, sheet.rowCount)
            assertEquals(5, sheet.colCount)
            assertFalse(sheet.truncated)

            val second = book.readSheet(1)
            assertEquals("Hello", second.text(1, 1))
            assertFalse(second.showGrid)
        }
    }

    @Test
    fun readsExcelsOwnLayout() {
        Workbook.open(fixture("shared.xlsx"), Locale.US).use { book ->
            val sheet = book.readSheet(0)
            assertEquals("Region", sheet.text(0, 0))
            assertEquals("Rich text", sheet.text(0, 1)) // phonetic guide left out
            assertEquals("Line\rbreak", sheet.text(0, 2))
            assertEquals("€1,234.50", sheet.text(1, 0))
            assertEquals("1/1/1904", sheet.text(1, 1)) // 1904 date system
            assertEquals("#DIV/0!", sheet.text(1, 2))
            assertEquals("Region", sheet.text(1, 3))

            val style = sheet.style(sheet.rows.getValue(0).styles[0])
            assertTrue(style.bold && style.italic)
            assertEquals(14f, style.fontSize)
            assertEquals(0xFFFFFF00.toInt(), style.fill) // indexed colour 13
            assertEquals(HAlign.CENTER, style.horizontal)
            // Theme accent 1 darkened 25%, which Excel shows as #2F5597.
            val color = style.textColor!!
            assertEquals(0x2F, color shr 16 and 0xFF)
            assertEquals(0x55, color shr 8 and 0xFF)
            assertTrue((color and 0xFF) in 0x96..0x97)
        }
        OfficePackage(fixture("shared.xlsx")).use { pkg ->
            assertEquals("Quarterly figures" to "Test Author", OfficeFiles.properties(pkg))
            assertEquals(BookFormat.XLSX, OfficeFiles.formatOf(pkg))
        }
    }

    @Test
    fun stopsAtTheCellLimit() {
        Workbook.open(fixture("sheet.xlsx"), Locale.US).use { book ->
            val sheet = book.readSheet(0, maxCells = 3)
            assertTrue(sheet.truncated)
            assertEquals(3, sheet.rows.values.sumOf { it.cols.size })
        }
    }

    @Test
    fun references() {
        assertEquals(0, Workbook.columnOf("A1"))
        assertEquals(27, Workbook.columnOf("AB12"))
        assertEquals(0, Workbook.columnOf("\$A\$1"))
        assertEquals("A", Workbook.columnName(0))
        assertEquals("Z", Workbook.columnName(25))
        assertEquals("AA", Workbook.columnName(26))
        assertEquals("XFD", Workbook.columnName(16_383))
        assertEquals(CellRange(0, 0, 1, 2), Workbook.rangeOf("A1:C2"))
        assertEquals(CellRange(4, 1, 4, 1), Workbook.rangeOf("B5"))
        assertEquals(140f, Workbook.columnPixels(20f))
    }
}
