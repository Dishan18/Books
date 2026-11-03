package io.github.dishan18.books.office.sheet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The colours a sheet is drawn in, from the app theme. */
data class GridColors(
    val background: Int,
    val text: Int,
    val grid: Int,
    val header: Int,
    val headerText: Int,
    val selection: Int,
    val selectionHeader: Int,
)

@Composable
fun rememberGridColors(): GridColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        GridColors(
            background = scheme.surface.toArgb(),
            text = scheme.onSurface.toArgb(),
            grid = scheme.outlineVariant.toArgb(),
            header = scheme.surfaceContainerHigh.toArgb(),
            headerText = scheme.onSurfaceVariant.toArgb(),
            selection = scheme.primary.toArgb(),
            selectionHeader = scheme.primaryContainer.toArgb(),
        )
    }
}

/**
 * A sheet's cells, drawn directly so even large sheets scroll smoothly. Drag to scroll, pinch to
 * zoom, tap a cell to select it ([onSelect] gets the cell, or its merged block).
 */
@Composable
fun SheetGrid(sheet: Sheet, selected: CellRange?, onSelect: (CellRange?) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val colors = rememberGridColors()
    val layout = remember(sheet) { GridLayout(sheet) }
    val painter = remember(sheet, colors) { SheetPainter(sheet, layout, colors) }
    var zoom by remember(sheet) { mutableFloatStateOf(1f) }
    var scrollX by remember(sheet) { mutableFloatStateOf(0f) }
    var scrollY by remember(sheet) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val currentSelection by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)

    Canvas(
        modifier
            .pointerInput(sheet) {
                val decay = splineBasedDecay<Float>(this)
                var fling: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = false
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers == 0) break
                        if (fingers > 1) multiTouch = true
                        val pan = event.calculatePan()
                        val zoomChange = event.calculateZoom()
                        if (!moved && (abs(pan.x) + abs(pan.y) > 0f || zoomChange != 1f)) {
                            val travel = (event.changes.first().position - down.position).getDistance()
                            if (travel > viewConfiguration.touchSlop || fingers > 1) moved = true
                        }
                        if (moved) {
                            val k = density * zoom
                            if (zoomChange != 1f) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val anchor = layout.contentAt(centroid, scrollX, scrollY, k)
                                zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val k2 = density * zoom
                                scrollX = layout.scrollToKeep(anchor.x, centroid.x, k2, horizontal = true)
                                scrollY = layout.scrollToKeep(anchor.y, centroid.y, k2, horizontal = false)
                            }
                            val k2 = density * zoom
                            scrollX = layout.clampX(scrollX - pan.x / k2, size.width.toFloat(), k2)
                            scrollY = layout.clampY(scrollY - pan.y / k2, size.height.toFloat(), k2)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    } while (event.changes.any { it.pressed })

                    if (!moved) {
                        val k = density * zoom
                        val cell = layout.cellAt(down.position, scrollX, scrollY, k)
                        select(if (cell == null || cell == currentSelection) null else cell)
                    } else if (!multiTouch) {
                        val velocity = tracker.calculateVelocity()
                        fling = scope.launch {
                            launch {
                                var last = 0f
                                AnimationState(0f, -velocity.x).animateDecay(decay) {
                                    val k = density * zoom
                                    scrollX = layout.clampX(scrollX + (value - last) / k, size.width.toFloat(), k)
                                    last = value
                                }
                            }
                            launch {
                                var last = 0f
                                AnimationState(0f, -velocity.y).animateDecay(decay) {
                                    val k = density * zoom
                                    scrollY = layout.clampY(scrollY + (value - last) / k, size.height.toFloat(), k)
                                    last = value
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val k = density * zoom
        // Reading the scroll state here redraws on every change.
        val x = layout.clampX(scrollX, size.width, k)
        val y = layout.clampY(scrollY, size.height, k)
        drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, x, y, k, selected) }
    }
}

private const val MIN_ZOOM = 0.35f
private const val MAX_ZOOM = 4f

/** Where every row and column sits, in sheet units (one unit is one dp at 100%). */
private class GridLayout(val sheet: Sheet) {
    val cols = max(sheet.colCount, sheet.frozenCols) + EXTRA_COLS
    val rows = max(sheet.rowCount, sheet.frozenRows) + EXTRA_ROWS
    val colX = FloatArray(cols + 1).also { a -> for (c in 0 until cols) a[c + 1] = a[c] + sheet.colWidth(c) }
    val rowY = FloatArray(rows + 1).also { a -> for (r in 0 until rows) a[r + 1] = a[r] + rowHeight(r) }
    val frozenCols = sheet.frozenCols.coerceAtMost(cols)
    val frozenRows = sheet.frozenRows.coerceAtMost(rows)
    val frozenWidth = colX[frozenCols]
    val frozenHeight = rowY[frozenRows]
    val rowHeaderWidth = 14f + 7f * rows.toString().length
    val colHeaderHeight = 20f

    /**
     * Screen x of column edge [c] (the left edge of column c, the right edge of column c - 1), in
     * the frozen columns or the scrolling ones. An edge on the boundary belongs to both, so the
     * caller says which side it's drawing.
     */
    fun x(c: Int, scrollX: Float, k: Float, frozen: Boolean) =
        if (frozen) (rowHeaderWidth + colX[c]) * k else (rowHeaderWidth + colX[c] - scrollX) * k

    fun y(r: Int, scrollY: Float, k: Float, frozen: Boolean) =
        if (frozen) (colHeaderHeight + rowY[r]) * k else (colHeaderHeight + rowY[r] - scrollY) * k

    /** Left and right edges of column [c], and top and bottom of row [r], wherever they are. */
    fun left(c: Int, scrollX: Float, k: Float) = x(c, scrollX, k, c < frozenCols)
    fun right(c: Int, scrollX: Float, k: Float) = x(c + 1, scrollX, k, c < frozenCols)
    fun top(r: Int, scrollY: Float, k: Float) = y(r, scrollY, k, r < frozenRows)
    fun bottom(r: Int, scrollY: Float, k: Float) = y(r + 1, scrollY, k, r < frozenRows)

    fun clampX(scroll: Float, viewWidth: Float, k: Float): Float {
        val visible = viewWidth / k - rowHeaderWidth - frozenWidth
        return scroll.coerceIn(0f, max(0f, colX[cols] - frozenWidth - visible))
    }

    fun clampY(scroll: Float, viewHeight: Float, k: Float): Float {
        val visible = viewHeight / k - colHeaderHeight - frozenHeight
        return scroll.coerceIn(0f, max(0f, rowY[rows] - frozenHeight - visible))
    }

    /** The sheet point (in units) under a screen point. */
    fun contentAt(p: Offset, scrollX: Float, scrollY: Float, k: Float): Offset {
        val ux = p.x / k - rowHeaderWidth
        val uy = p.y / k - colHeaderHeight
        return Offset(if (ux < frozenWidth) ux else ux + scrollX, if (uy < frozenHeight) uy else uy + scrollY)
    }

    /** The scroll that puts sheet point [content] back under screen position [screen] at scale [k]. */
    fun scrollToKeep(content: Float, screen: Float, k: Float, horizontal: Boolean): Float {
        val header = if (horizontal) rowHeaderWidth else colHeaderHeight
        val frozen = if (horizontal) frozenWidth else frozenHeight
        if (content < frozen) return 0f
        return content - (screen / k - header)
    }

    fun cellAt(p: Offset, scrollX: Float, scrollY: Float, k: Float): CellRange? {
        if (p.x / k < rowHeaderWidth || p.y / k < colHeaderHeight) return null
        val content = contentAt(p, scrollX, scrollY, k)
        val c = indexAt(colX, content.x)
        val r = indexAt(rowY, content.y)
        if (c < 0 || r < 0) return null
        return sheet.merges.firstOrNull { it.contains(r, c) } ?: CellRange(r, c, r, c)
    }

    /** The row or column containing [value], or -1. */
    fun indexAt(edges: FloatArray, value: Float): Int {
        if (value < 0 || value >= edges.last()) return -1
        var lo = 0
        var hi = edges.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (edges[mid] <= value) lo = mid else hi = mid - 1
        }
        // Hidden (zero-size) rows and columns are never picked.
        while (lo + 1 < edges.size - 1 && edges[lo + 1] == edges[lo]) lo++
        return lo
    }

    /**
     * A row's height, grown to fit wrapped text when the file doesn't fix it, as Excel does. Only
     * rows with wrapped cells are measured, so ordinary sheets cost nothing extra.
     */
    private fun rowHeight(r: Int): Float {
        val base = sheet.rowHeight(r)
        if (sheet.hasRowHeight(r)) return base
        val row = sheet.rows[r] ?: return base
        var needed = base
        for (i in row.cols.indices) {
            if (row.kinds[i] != CellKind.TEXT || row.texts[i].isEmpty()) continue
            val style = sheet.style(row.styles[i])
            if (!style.wrap) continue
            val width = (sheet.colWidth(row.cols[i]) - 5f - style.indent * 9f).toInt()
            if (width < 4) continue
            measure.textSize = style.fontSize * Workbook.PX_PER_POINT
            measure.typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val height = StaticLayout.Builder.obtain(row.texts[i], 0, row.texts[i].length, measure, width).setIncludePad(false).build().height
            needed = max(needed, height + 5f)
        }
        return min(needed, MAX_AUTO_HEIGHT)
    }

    private companion object {
        const val EXTRA_COLS = 4
        const val EXTRA_ROWS = 20
        const val MAX_AUTO_HEIGHT = 409f * Workbook.PX_PER_POINT // Excel's tallest row
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}

/** Draws cells, gridlines, merged blocks, headers and the selection. */
private class SheetPainter(private val sheet: Sheet, private val layout: GridLayout, private val colors: GridColors) {

    private val fillPaint = Paint()
    private val gridPaint = Paint().apply { color = colors.grid; strokeWidth = 1f }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.headerText; textAlign = Paint.Align.CENTER }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = colors.selection }
    private val wrapped = LruCache<String, StaticLayout>(200)
    private val typefaces = arrayOf(Typeface.DEFAULT, Typeface.DEFAULT_BOLD, Typeface.create(Typeface.DEFAULT, Typeface.ITALIC), Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC))

    /** Cells hidden under a merged block, by row and column (not the block's own top-left cell). */
    private val covered: Map<Long, CellRange> = HashMap<Long, CellRange>().also { map ->
        var budget = 500_000
        for (m in sheet.merges) {
            val area = (m.lastRow - m.firstRow + 1).toLong() * (m.lastCol - m.firstCol + 1)
            if (area > budget) continue
            budget -= area.toInt()
            for (r in m.firstRow..m.lastRow) for (c in m.firstCol..m.lastCol) if (r != m.firstRow || c != m.firstCol) map[key(r, c)] = m
        }
    }
    private val mergeStarts: Map<Long, CellRange> = sheet.merges.associateBy { key(it.firstRow, it.firstCol) }

    private fun key(r: Int, c: Int) = r.toLong() * 20_000 + c

    fun draw(canvas: Canvas, width: Float, height: Float, scrollX: Float, scrollY: Float, k: Float, selected: CellRange?) {
        canvas.drawColor(colors.background)
        gridPaint.strokeWidth = max(1f, k * 0.5f)
        val left = layout.rowHeaderWidth * k
        val top = layout.colHeaderHeight * k
        val frozenRight = left + layout.frozenWidth * k
        val frozenBottom = top + layout.frozenHeight * k

        val scrolledCols = visible(layout.colX, layout.frozenCols, layout.frozenWidth + scrollX, (width - frozenRight) / k)
        val scrolledRows = visible(layout.rowY, layout.frozenRows, layout.frozenHeight + scrollY, (height - frozenBottom) / k)
        val frozenCols = 0 until layout.frozenCols
        val frozenRows = 0 until layout.frozenRows

        region(canvas, RectF(frozenRight, frozenBottom, width, height), scrolledRows, scrolledCols, scrollX, scrollY, k, rowsFrozen = false, colsFrozen = false)
        if (!frozenRows.isEmpty()) region(canvas, RectF(frozenRight, top, width, frozenBottom), frozenRows, scrolledCols, scrollX, scrollY, k, rowsFrozen = true, colsFrozen = false)
        if (!frozenCols.isEmpty()) region(canvas, RectF(left, frozenBottom, frozenRight, height), scrolledRows, frozenCols, scrollX, scrollY, k, rowsFrozen = false, colsFrozen = true)
        if (!frozenRows.isEmpty() && !frozenCols.isEmpty()) {
            region(canvas, RectF(left, top, frozenRight, frozenBottom), frozenRows, frozenCols, scrollX, scrollY, k, rowsFrozen = true, colsFrozen = true)
        }

        selected?.let { drawSelection(canvas, it, RectF(left, top, width, height), scrollX, scrollY, k) }
        headers(canvas, width, height, scrolledRows, scrolledCols, scrollX, scrollY, k, selected)

        // The freeze lines, a little stronger than the grid.
        if (layout.frozenCols > 0) canvas.drawLine(frozenRight, 0f, frozenRight, height, gridPaint.withStrongerLine())
        if (layout.frozenRows > 0) canvas.drawLine(0f, frozenBottom, width, frozenBottom, gridPaint.withStrongerLine())
    }

    private fun Paint.withStrongerLine(): Paint = Paint(this).apply { strokeWidth = this@withStrongerLine.strokeWidth * 2.5f; color = colors.headerText; alpha = 110 }

    /** Rows or columns from [start] whose span overlaps [from, from + length). */
    private fun visible(edges: FloatArray, start: Int, from: Float, length: Float): IntRange {
        val count = edges.size - 1
        if (start >= count || length <= 0f) return IntRange.EMPTY
        var first = layout.indexAt(edges, from).coerceAtLeast(start)
        if (first < 0) first = start
        var last = first
        while (last + 1 < count && edges[last + 1] < from + length) last++
        return first..last
    }

    private fun region(
        canvas: Canvas,
        clip: RectF,
        rows: IntRange,
        cols: IntRange,
        scrollX: Float,
        scrollY: Float,
        k: Float,
        rowsFrozen: Boolean,
        colsFrozen: Boolean,
    ) {
        if (rows.isEmpty() || cols.isEmpty() || clip.isEmpty) return
        canvas.save()
        canvas.clipRect(clip)
        // Every edge in a region is measured the same way, including the one it shares with the next region.
        fun x(c: Int) = layout.x(c, scrollX, k, colsFrozen)
        fun y(r: Int) = layout.y(r, scrollY, k, rowsFrozen)

        // Fills.
        for (r in rows) {
            val row = sheet.rows[r] ?: continue
            forCells(row, cols.first, cols.last) { i, c ->
                if (covered.containsKey(key(r, c)) || mergeStarts.containsKey(key(r, c))) return@forCells
                sheet.style(row.styles[i]).fill?.let { color ->
                    fillPaint.color = color
                    canvas.drawRect(x(c), y(r), x(c + 1), y(r + 1), fillPaint)
                }
            }
        }
        // Gridlines.
        if (sheet.showGrid) {
            for (c in cols.first..cols.last + 1) canvas.drawLine(x(c), clip.top, x(c), clip.bottom, gridPaint)
            for (r in rows.first..rows.last + 1) canvas.drawLine(clip.left, y(r), clip.right, y(r), gridPaint)
        }
        // Merged blocks: one cell's worth of colour and text across the whole block.
        for (m in sheet.merges) {
            if (m.lastRow < rows.first || m.firstRow > rows.last || m.lastCol < cols.first || m.firstCol > cols.last) continue
            val rect = RectF(x(m.firstCol), y(m.firstRow), x(m.lastCol + 1), y(m.lastRow + 1))
            val row = sheet.rows[m.firstRow]
            val i = row?.indexOf(m.firstCol) ?: -1
            val style = if (i >= 0) sheet.style(row!!.styles[i]) else null
            fillPaint.color = style?.fill ?: colors.background
            canvas.drawRect(rect, fillPaint)
            if (sheet.showGrid) {
                gridPaint.style = Paint.Style.STROKE
                canvas.drawRect(rect, gridPaint)
                gridPaint.style = Paint.Style.FILL
            }
            if (i >= 0 && row!!.texts[i].isNotEmpty()) drawText(canvas, row.texts[i], row.kinds[i], style!!, rect, rect.right, k)
        }
        // Text. A long label spills into empty cells to its right, as in Excel.
        for (r in rows) {
            val row = sheet.rows[r] ?: continue
            forCells(row, cols.first, cols.last, lookBehind = true) { i, c ->
                val text = row.texts[i]
                if (text.isEmpty() || covered.containsKey(key(r, c)) || mergeStarts.containsKey(key(r, c))) return@forCells
                val style = sheet.style(row.styles[i])
                val rect = RectF(x(c), y(r), x(c + 1), y(r + 1))
                var spill = rect.right
                if (row.kinds[i] == CellKind.TEXT && !style.wrap && (style.horizontal == HAlign.GENERAL || style.horizontal == HAlign.LEFT)) {
                    var next = c + 1
                    val nextIndex = i + 1
                    val limit = if (nextIndex < row.cols.size) row.cols[nextIndex] else Int.MAX_VALUE
                    while (next < limit && next <= cols.last + 1 && next < layout.cols && !covered.containsKey(key(r, next)) && !mergeStarts.containsKey(key(r, next))) next++
                    // An empty neighbour with only a colour still stops the spill once its text would begin.
                    if (nextIndex < row.cols.size && row.cols[nextIndex] == next && row.texts[nextIndex].isEmpty()) next++
                    spill = x(next.coerceAtMost(layout.cols))
                }
                drawText(canvas, text, row.kinds[i], style, rect, spill, k)
            }
        }
        canvas.restore()
    }

    /** Calls [block] for the cells in [row] between columns [from] and [to] (and the one before, with [lookBehind]). */
    private inline fun forCells(row: SheetRow, from: Int, to: Int, lookBehind: Boolean = false, block: (index: Int, col: Int) -> Unit) {
        var i = row.cols.binarySearch(from).let { if (it < 0) -it - 1 else it }
        if (lookBehind && i > 0) i--
        while (i < row.cols.size && row.cols[i] <= to) {
            block(i, row.cols[i])
            i++
        }
    }

    private fun drawText(canvas: Canvas, text: String, kind: CellKind, style: CellStyle, cell: RectF, spillRight: Float, k: Float) {
        if (cell.width() < 1f || cell.height() < 1f) return
        val background = style.fill ?: colors.background
        textPaint.color = readableColor(style.textColor, background)
        textPaint.typeface = typefaces[(if (style.bold) 1 else 0) + (if (style.italic) 2 else 0)]
        textPaint.isUnderlineText = style.underline
        textPaint.isStrikeThruText = style.strike
        textPaint.textSize = style.fontSize * Workbook.PX_PER_POINT * k
        val pad = 2.5f * k + style.indent * 9f * k
        val available = cell.width() - 2 * pad

        val align = when (style.horizontal) {
            HAlign.GENERAL -> when (kind) {
                CellKind.NUMBER -> HAlign.RIGHT
                CellKind.BOOLEAN, CellKind.ERROR -> HAlign.CENTER
                CellKind.TEXT -> HAlign.LEFT
            }
            HAlign.FILL, HAlign.JUSTIFY -> HAlign.LEFT
            else -> style.horizontal
        }

        canvas.save()
        canvas.clipRect(cell.left, cell.top, max(cell.right, spillRight), cell.bottom)
        if (style.wrap && kind == CellKind.TEXT && available > 1f) {
            val key = "$text|${available.toInt()}|${textPaint.textSize.toInt()}|${style.bold}|${style.italic}|$align"
            val laid = wrapped.get(key) ?: StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(textPaint), available.toInt())
                .setAlignment(
                    when (align) {
                        HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                        HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    },
                )
                .setIncludePad(false)
                .build().also { wrapped.put(key, it) }
            val top = when (style.vertical) {
                VAlign.TOP -> cell.top + pad / 2
                VAlign.CENTER -> cell.centerY() - laid.height / 2f
                VAlign.BOTTOM -> cell.bottom - pad / 2 - laid.height
            }
            laid.paint.color = textPaint.color
            canvas.translate(cell.left + pad, top)
            laid.draw(canvas)
            canvas.restore()
            return
        }

        var shown = text.substringBefore('\n')
        var width = textPaint.measureText(shown)
        if (kind == CellKind.NUMBER && width > available) {
            // A number never spills or gets cut: shrink it a little, or show #### as Excel does.
            val shrink = available / width
            if (shrink >= 0.75f) {
                textPaint.textSize *= shrink
                width = textPaint.measureText(shown)
            } else {
                val hash = textPaint.measureText("#")
                shown = "#".repeat(max(1, (available / hash).toInt()))
                width = textPaint.measureText(shown)
            }
        }
        val fm = textPaint.fontMetrics
        val baseline = when (style.vertical) {
            VAlign.TOP -> cell.top + pad / 2 - fm.ascent
            VAlign.CENTER -> cell.centerY() - (fm.ascent + fm.descent) / 2
            VAlign.BOTTOM -> cell.bottom - pad / 2 - fm.descent
        }
        val x = when (align) {
            HAlign.CENTER -> cell.centerX() - width / 2
            HAlign.RIGHT -> cell.right - pad - width
            else -> cell.left + pad
        }
        canvas.drawText(shown, x, baseline, textPaint)
        canvas.restore()
    }

    /** The cell's own text colour unless it would be hard to read on its background (say, black on the dark theme). */
    private fun readableColor(color: Int?, background: Int): Int {
        val fallback = if (luminance(background) > 0.45f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val auto = if (background == colors.background) colors.text else fallback
        color ?: return auto
        return if (contrast(color, background) < 2.2f) auto else color
    }

    private fun luminance(c: Int): Float {
        fun ch(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * ch(c shr 16 and 0xFF) + 0.7152f * ch(c shr 8 and 0xFF) + 0.0722f * ch(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun drawSelection(canvas: Canvas, range: CellRange, data: RectF, scrollX: Float, scrollY: Float, k: Float) {
        canvas.save()
        canvas.clipRect(data)
        selectionPaint.strokeWidth = 2f * k.coerceAtLeast(1f)
        canvas.drawRect(
            layout.left(range.firstCol, scrollX, k),
            layout.top(range.firstRow, scrollY, k),
            layout.right(range.lastCol, scrollX, k),
            layout.bottom(range.lastRow, scrollY, k),
            selectionPaint,
        )
        canvas.restore()
    }

    private fun headers(canvas: Canvas, width: Float, height: Float, rows: IntRange, cols: IntRange, scrollX: Float, scrollY: Float, k: Float, selected: CellRange?) {
        val left = layout.rowHeaderWidth * k
        val top = layout.colHeaderHeight * k
        headerPaint.textSize = 11f * Workbook.PX_PER_POINT * k * 0.85f
        val fm = headerPaint.fontMetrics

        fun columnHeader(c: Int) {
            val x0 = layout.left(c, scrollX, k)
            val x1 = layout.right(c, scrollX, k)
            if (x1 - x0 < 1f) return
            if (selected != null && c in selected.firstCol..selected.lastCol) {
                fillPaint.color = colors.selectionHeader
                canvas.drawRect(x0, 0f, x1, top, fillPaint)
            }
            canvas.drawLine(x1, 0f, x1, top, gridPaint)
            canvas.drawText(Workbook.columnName(c), (x0 + x1) / 2, top / 2 - (fm.ascent + fm.descent) / 2, headerPaint)
        }

        fun rowHeader(r: Int) {
            val y0 = layout.top(r, scrollY, k)
            val y1 = layout.bottom(r, scrollY, k)
            if (y1 - y0 < 1f) return
            if (selected != null && r in selected.firstRow..selected.lastRow) {
                fillPaint.color = colors.selectionHeader
                canvas.drawRect(0f, y0, left, y1, fillPaint)
            }
            canvas.drawLine(0f, y1, left, y1, gridPaint)
            canvas.drawText((r + 1).toString(), left / 2, (y0 + y1) / 2 - (fm.ascent + fm.descent) / 2, headerPaint)
        }

        // Column letters.
        canvas.save()
        fillPaint.color = colors.header
        canvas.drawRect(0f, 0f, width, top, fillPaint)
        canvas.clipRect(left + layout.frozenWidth * k, 0f, width, top)
        cols.forEach(::columnHeader)
        canvas.restore()
        canvas.save()
        canvas.clipRect(left, 0f, left + layout.frozenWidth * k, top)
        (0 until layout.frozenCols).forEach(::columnHeader)
        canvas.restore()

        // Row numbers.
        canvas.save()
        fillPaint.color = colors.header
        canvas.drawRect(0f, top, left, height, fillPaint)
        canvas.clipRect(0f, top + layout.frozenHeight * k, left, height)
        rows.forEach(::rowHeader)
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, top, left, top + layout.frozenHeight * k)
        (0 until layout.frozenRows).forEach(::rowHeader)
        canvas.restore()

        // The corner, and the edges between headers and cells.
        fillPaint.color = colors.header
        canvas.drawRect(0f, 0f, left, top, fillPaint)
        canvas.drawLine(0f, top, width, top, gridPaint)
        canvas.drawLine(left, 0f, left, height, gridPaint)
    }
}
