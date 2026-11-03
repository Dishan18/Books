package io.github.dishan18.books.office

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.office.sheet.CellKind
import io.github.dishan18.books.office.sheet.Workbook
import io.github.dishan18.books.office.slides.PresentationReader
import io.github.dishan18.books.office.slides.SlideDocument
import java.io.File

/**
 * Library covers for Excel and PowerPoint files, which have none of their own: the first slide, or
 * the top of the first sheet, on a book-shaped (2:3) card.
 */
object OfficeCovers {

    fun render(file: File, format: BookFormat, width: Int): Bitmap? = when (format) {
        BookFormat.PPTX -> slideCover(file, width)
        BookFormat.XLSX -> sheetCover(file, width)
        else -> null
    }

    private fun slideCover(file: File, width: Int): Bitmap = OfficePackage(file).use { pkg ->
        val presentation = PresentationReader(pkg).read()
        val slide = SlideDocument.renderOnce(pkg, presentation, 0, width)
        val height = width * 3 / 2
        val cover = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        // The card takes the slide's own average colour, a little deeper, so it looks like one piece.
        val average = Bitmap.createScaledBitmap(slide, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3).also { Color.colorToHSV(average, it) }
        hsv[2] *= 0.8f
        canvas.drawColor(Color.HSVToColor(hsv))
        val margin = width * 0.08f
        val slideWidth = width - 2 * margin
        val slideHeight = slideWidth * slide.height / slide.width
        val top = (height - slideHeight) / 2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        canvas.drawRect(RectF(margin + 3, top + 5, margin + slideWidth + 3, top + slideHeight + 5), shadow)
        canvas.drawBitmap(slide, null, RectF(margin, top, margin + slideWidth, top + slideHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        slide.recycle()
        cover
    }

    /** A sheet of paper showing the first rows and columns, small enough to read as "a spreadsheet". */
    private fun sheetCover(file: File, width: Int): Bitmap = Workbook.open(file).use { workbook ->
        val sheet = workbook.readSheet(0, maxCells = 2_000)
        val height = width * 3 / 2
        val cover = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        canvas.drawColor(Color.WHITE)
        val header = height * 0.045f
        val rowHeight = height * 0.04f
        val rowLabel = width * 0.09f
        val columns = 4
        val colWidth = (width - rowLabel) / columns
        val grid = Paint().apply { color = 0xFFDADCE0.toInt(); strokeWidth = 1f }
        val band = Paint().apply { color = 0xFFF1F3F4.toInt() }
        canvas.drawRect(0f, 0f, width.toFloat(), header, band)
        canvas.drawRect(0f, 0f, rowLabel, height.toFloat(), band)
        val labels = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5F6368.toInt()
            textSize = header * 0.5f
            textAlign = Paint.Align.CENTER
        }
        for (c in 0 until columns) {
            canvas.drawText(Workbook.columnName(c), rowLabel + colWidth * (c + 0.5f), header * 0.68f, labels)
        }
        val rows = ((height - header) / rowHeight).toInt()
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = rowHeight * 0.48f }
        for (r in 0 until rows) {
            val y = header + r * rowHeight
            canvas.drawText((r + 1).toString(), rowLabel / 2f, y + rowHeight * 0.66f, labels)
            val row = sheet.rows[r] ?: continue
            for (i in row.cols.indices) {
                val c = row.cols[i]
                if (c >= columns) break
                val style = sheet.style(row.styles[i])
                val left = rowLabel + c * colWidth
                style.fill?.let { canvas.drawRect(left, y, left + colWidth, y + rowHeight, Paint().apply { color = it }) }
                val value = row.texts[i]
                if (value.isEmpty()) continue
                text.color = style.textColor ?: Color.BLACK
                text.isFakeBoldText = style.bold
                val shown = TextUtils.ellipsize(value, text, colWidth - rowHeight * 0.3f, TextUtils.TruncateAt.END).toString()
                val x = if (row.kinds[i] == CellKind.NUMBER) left + colWidth - rowHeight * 0.15f - text.measureText(shown) else left + rowHeight * 0.15f
                canvas.drawText(shown, x, y + rowHeight * 0.68f, text)
            }
        }
        for (c in 0..columns) canvas.drawLine(rowLabel + c * colWidth, 0f, rowLabel + c * colWidth, height.toFloat(), grid)
        for (r in 0..rows) canvas.drawLine(0f, header + r * rowHeight, width.toFloat(), header + r * rowHeight, grid)
        canvas.drawLine(0f, header, width.toFloat(), header, grid)
        cover
    }
}
