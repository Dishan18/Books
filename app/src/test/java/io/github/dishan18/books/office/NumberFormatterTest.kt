package io.github.dishan18.books.office

import io.github.dishan18.books.office.sheet.NumberFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class NumberFormatterTest {

    private val f = NumberFormatter(Locale.US)

    /** Excel's serial number for a date (1900 system). */
    private fun serial(date: LocalDate) = (date.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()).toDouble()

    @Test
    fun general() {
        assertEquals("0", f.format(0.0, "General"))
        assertEquals("123", f.format(123.0, "General"))
        assertEquals("-0.5", f.format(-0.5, "General"))
        assertEquals("0.3", f.format(0.1 + 0.2, "General"))
        assertEquals("12345678901", f.format(12345678901.0, "General"))
        assertEquals("1.23457E+13", f.format(12345678901234.0, "General"))
        assertEquals("1E-10", f.format(1e-10, "General"))
        assertEquals("0.333333333", f.format(1.0 / 3, "General"))
        assertEquals("5 kg", f.format(5.0, "General\" kg\""))
    }

    @Test
    fun decimalsGroupingAndPercent() {
        assertEquals("1,234.50", f.format(1234.5, "#,##0.00"))
        assertEquals("1,235", f.format(1234.5, "#,##0"))
        assertEquals("0", f.format(0.2, "#,##0"))
        assertEquals("12.50", f.format(12.5, ".00"))
        assertEquals(".50", f.format(0.5, "#.00"))
        assertEquals("25.6%", f.format(0.256, "0.0%"))
        assertEquals("3", f.format(2.5, "0"))
        assertEquals("2.68", f.format(2.675, "0.00"))
        assertEquals("1.5", f.format(1.5, "0.##"))
        assertEquals("2", f.format(2.0, "0.##").trimEnd('.'))
        assertEquals("-0.00", f.format(-0.001, "0.00"))
    }

    @Test
    fun scalingAndLiterals() {
        assertEquals("1,235", f.format(1_234_567.0, "#,##0,"))
        assertEquals("1.2", f.format(1_234_567.0, "0.0,,"))
        assertEquals("555-1234", f.format(5_551_234.0, "000-0000"))
        assertEquals("€1,234.50", f.format(1234.5, "\"€\"#,##0.00"))
        assertEquals("€1,234.50", f.format(1234.5, "[$€-407]#,##0.00"))
        assertEquals("12 units", f.format(12.0, "0 \"units\""))
    }

    @Test
    fun sections() {
        val accounting = "\"$\"#,##0.00_);(\"$\"#,##0.00)"
        assertEquals("$42.00 ", f.format(42.0, accounting))
        assertEquals("($42.00)", f.format(-42.0, accounting))
        assertEquals("zero", f.format(0.0, "0;-0;\"zero\""))
        assertEquals("-5", f.format(-5.0, "0"))
        assertEquals("neg", f.format(-5.0, "[<0]\"neg\";\"pos\""))
        assertEquals("pos", f.format(5.0, "[<0]\"neg\";\"pos\""))
        assertEquals("7", f.format(7.0, "@"))
    }

    @Test
    fun scientificAndFractions() {
        assertEquals("1.23E+04", f.format(12345.0, "0.00E+00"))
        assertEquals("1.23E-04", f.format(0.000123, "0.00E+00"))
        assertEquals("12.3E+3", f.format(12345.0, "##0.0E+0"))
        assertEquals("1 1/2", f.format(1.5, "# ?/?").trim())
        assertEquals("1/3", f.format(1.0 / 3, "# ??/??").trim())
        assertEquals("2", f.format(2.0, "# ?/?").trim())
        assertEquals("4/10", f.format(0.35, "?/10").trim())
        assertEquals("3/16", f.format(0.1875, "# ?/16").trim())
    }

    @Test
    fun datesAndTimes() {
        val day = serial(LocalDate.of(2024, 3, 15))
        assertEquals("15-Mar-24", f.format(day, "d-mmm-yy"))
        assertEquals("Friday, March 15", f.format(day, "dddd, mmmm d"))
        assertEquals("3/15/2024", f.format(day, NumberFormatter.SHORT_DATE))
        assertEquals("2024-03-15 13:45:30", f.format(day + (13 * 3600 + 45 * 60 + 30) / 86_400.0, "yyyy-mm-dd hh:mm:ss"))
        assertEquals("3/15/24 12:00", f.format(day + 0.5, "m/d/yy h:mm"))
        assertEquals("6:00 PM", f.format(0.75, "h:mm AM/PM"))
        assertEquals("6:00 p", f.format(0.75, "h:mm a/p"))
        assertEquals("36:00:00", f.format(1.5, "[h]:mm:ss"))
        assertEquals("01:30", f.format(90 / 86_400.0, "mm:ss"))
        assertEquals("12:00:00.5", f.format((12 * 3600 + 0.5) / 86_400.0, "hh:mm:ss.0"))
        // Shown rounded, as Excel does: 12:59:59.6 is 13:00.
        assertEquals("13:00", f.format((12 * 3600 + 59 * 60 + 59.6) / 86_400.0, "h:mm"))
        assertEquals("########", f.format(-1.0, "yyyy-mm-dd"))
        assertTrue(f.isDate("d-mmm-yy"))
        assertTrue(f.isDate(NumberFormatter.SHORT_DATE))
        assertFalse(f.isDate("#,##0.00"))
        assertFalse(f.isDate("0.00E+00"))
    }

    @Test
    fun excelsCalendar() {
        assertEquals("1900-01-01", f.format(1.0, "yyyy-mm-dd"))
        // Excel keeps Lotus 1-2-3's 29 February 1900.
        assertEquals("1900-02-29", f.format(60.0, "yyyy-mm-dd"))
        assertEquals("1900-03-01", f.format(61.0, "yyyy-mm-dd"))
        assertEquals("1904-01-02", NumberFormatter(Locale.US, date1904 = true).format(1.0, "yyyy-mm-dd"))
    }

    @Test
    fun localSeparators() {
        val german = NumberFormatter(Locale.GERMANY)
        assertEquals("1.234,50", german.format(1234.5, "#,##0.00"))
        assertEquals("0,3", german.format(0.1 + 0.2, "General"))
    }
}
