package io.github.dishan18.books.office.sheet

import io.github.dishan18.books.office.OfficeFileException
import io.github.dishan18.books.office.OfficePackage
import io.github.dishan18.books.office.StopParsing
import io.github.dishan18.books.office.XmlNode
import io.github.dishan18.books.office.drawing.ThemeColors
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.Closeable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

enum class HAlign { GENERAL, LEFT, CENTER, RIGHT, FILL, JUSTIFY }

enum class VAlign { TOP, CENTER, BOTTOM }

/** How a cell's value reads, which sets where it sits when the style doesn't say. */
enum class CellKind { TEXT, NUMBER, BOOLEAN, ERROR }

/** How a cell looks. Colours are ARGB; null means automatic. Sizes are in points. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontSize: Float = 11f,
    val textColor: Int? = null,
    val fill: Int? = null,
    val horizontal: HAlign = HAlign.GENERAL,
    val vertical: VAlign = VAlign.BOTTOM,
    val wrap: Boolean = false,
    val indent: Int = 0,
)

data class CellRange(val firstRow: Int, val firstCol: Int, val lastRow: Int, val lastCol: Int) {
    fun contains(row: Int, col: Int) = row in firstRow..lastRow && col in firstCol..lastCol
}

data class SheetInfo(val name: String, val part: String)

/** The cells of one row, sorted by column. Rows and columns are counted from 0. */
class SheetRow(val cols: IntArray, val texts: Array<String>, val styles: IntArray, val kinds: Array<CellKind>) {
    fun indexOf(col: Int): Int = cols.binarySearch(col)
}

/**
 * One sheet, ready to draw. Sizes are in pixels at 96 dpi, the unit Excel itself lays sheets out
 * in. Values are already formatted as Excel shows them.
 */
class Sheet(
    val name: String,
    val rowCount: Int,
    val colCount: Int,
    private val defaultColWidth: Float,
    private val defaultRowHeight: Float,
    private val colWidths: Map<Int, Float>,
    private val rowHeights: Map<Int, Float>,
    val rows: Map<Int, SheetRow>,
    val merges: List<CellRange>,
    val styles: List<CellStyle>,
    /** True when the sheet had more cells than can be shown, and only the first part was read. */
    val truncated: Boolean,
    /** Rows and columns frozen in place at the top and left ("Freeze panes"). */
    val frozenRows: Int = 0,
    val frozenCols: Int = 0,
    val showGrid: Boolean = true,
) {
    fun colWidth(col: Int): Float = colWidths[col] ?: defaultColWidth

    fun rowHeight(row: Int): Float = rowHeights[row] ?: defaultRowHeight

    /** False when the file leaves row [row] at its natural height, which Excel grows to fit wrapped text. */
    fun hasRowHeight(row: Int): Boolean = rowHeights.containsKey(row)

    fun style(index: Int): CellStyle = styles.getOrElse(index) { styles.firstOrNull() ?: CellStyle() }

    /** The text in a cell, or null when it's empty. */
    fun text(row: Int, col: Int): String? {
        val r = rows[row] ?: return null
        val i = r.indexOf(col)
        return if (i >= 0) r.texts[i].takeIf { it.isNotEmpty() } else null
    }

    val isEmpty get() = rows.isEmpty()
}

/** An Excel workbook. Sheets are read one at a time, when shown. */
class Workbook private constructor(
    private val pkg: OfficePackage,
    val sheets: List<SheetInfo>,
    private val sharedStrings: List<String>,
    private val styles: List<CellStyle>,
    private val formats: List<String>,
    private val formatter: NumberFormatter,
) : Closeable {

    /** Reads sheet [index]. Stops after [maxCells] cells, marking the sheet [Sheet.truncated]. */
    fun readSheet(index: Int, maxCells: Int = MAX_CELLS): Sheet {
        val info = sheets[index]
        val handler = SheetHandler(info.name, maxCells)
        try {
            pkg.sax(info.part, handler)
        } catch (_: StopParsing) {
            handler.truncated = true
        }
        return handler.build()
    }

    override fun close() = pkg.close()

    private inner class SheetHandler(private val name: String, private val maxCells: Int) : DefaultHandler() {
        var truncated = false
        private var defaultColWidth = DEFAULT_COL_WIDTH_PX
        private var defaultRowHeight = DEFAULT_ROW_HEIGHT_PX
        private val colWidths = HashMap<Int, Float>()
        private val rowHeights = HashMap<Int, Float>()
        private val rows = HashMap<Int, SheetRow>()
        private val merges = mutableListOf<CellRange>()
        private var cellCount = 0
        private var maxRow = -1
        private var maxCol = -1
        private var frozenRows = 0
        private var frozenCols = 0
        private var showGrid = true

        // The row being read.
        private var row = -1
        private var lastCol = -1
        private val rowCols = ArrayList<Int>()
        private val rowTexts = ArrayList<String>()
        private val rowStyles = ArrayList<Int>()
        private val rowKinds = ArrayList<CellKind>()

        // The cell being read.
        private var col = -1
        private var type = "n"
        private var style = 0
        private val value = StringBuilder()
        private val formula = StringBuilder()
        private var inValue = false
        private var inInline = false
        private var inInlineText = false
        private var inPhonetic = false
        private var inFormula = false
        private var hasValue = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            when (localName) {
                "sheetView" -> if (attributes.getValue("showGridLines").isFalse()) showGrid = false
                "pane" -> if (attributes.getValue("state") == "frozen" || attributes.getValue("state") == "frozenSplit") {
                    frozenCols = attributes.getValue("xSplit")?.toDoubleOrNull()?.toInt()?.coerceIn(0, MAX_FROZEN) ?: 0
                    frozenRows = attributes.getValue("ySplit")?.toDoubleOrNull()?.toInt()?.coerceIn(0, MAX_FROZEN) ?: 0
                }
                "sheetFormatPr" -> {
                    attributes.getValue("defaultColWidth")?.toFloatOrNull()?.let { defaultColWidth = columnPixels(it) }
                        ?: attributes.getValue("baseColWidth")?.toFloatOrNull()?.let { defaultColWidth = baseColumnPixels(it) }
                    attributes.getValue("defaultRowHeight")?.toFloatOrNull()?.let { defaultRowHeight = it * PX_PER_POINT }
                }
                "col" -> {
                    val min = attributes.getValue("min")?.toIntOrNull() ?: return
                    val max = (attributes.getValue("max")?.toIntOrNull() ?: min).coerceAtMost(MAX_COLUMNS)
                    val hidden = attributes.getValue("hidden").isTrue()
                    val width = attributes.getValue("width")?.toFloatOrNull()
                    if (!hidden && width == null) return
                    val px = if (hidden) 0f else columnPixels(width!!)
                    // A "col" can cover thousands of empty columns; only store the ones that differ.
                    for (c in (min - 1) until max) {
                        if (c > MAX_STORED_COLUMN && px == defaultColWidth) break
                        colWidths[c] = px
                    }
                }
                "row" -> {
                    row = attributes.getValue("r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                    lastCol = -1
                    rowCols.clear()
                    rowTexts.clear()
                    rowStyles.clear()
                    rowKinds.clear()
                    when {
                        attributes.getValue("hidden").isTrue() -> rowHeights[row] = 0f
                        else -> attributes.getValue("ht")?.toFloatOrNull()?.let { rowHeights[row] = it * PX_PER_POINT }
                    }
                }
                "c" -> {
                    val ref = attributes.getValue("r")
                    col = ref?.let(::columnOf) ?: (lastCol + 1)
                    type = attributes.getValue("t") ?: "n"
                    style = attributes.getValue("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    formula.setLength(0)
                    hasValue = false
                }
                "v" -> {
                    inValue = true
                    hasValue = true
                }
                "is" -> {
                    inInline = true
                    hasValue = true
                }
                "t" -> if (inInline && !inPhonetic) inInlineText = true
                "rPh" -> inPhonetic = true
                "f" -> inFormula = true
                "mergeCell" -> attributes.getValue("ref")?.let(::rangeOf)?.let { range ->
                    merges += range
                    maxRow = maxOf(maxRow, range.lastRow)
                    maxCol = maxOf(maxCol, range.lastCol)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            when {
                inValue || inInlineText -> value.appendRange(ch, start, start + length)
                inFormula -> formula.appendRange(ch, start, start + length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "rPh" -> inPhonetic = false
                "is" -> inInline = false
                "f" -> inFormula = false
                "c" -> endCell()
                "row" -> endRow()
            }
        }

        private fun endCell() {
            lastCol = col
            if (row < 0 || col < 0) return
            val styleIndex = style.takeIf { it in styles.indices } ?: 0
            // A formula whose result was never saved (some apps write an empty value): show the formula
            // itself. An empty text result ("str") is a real, empty answer.
            val unsaved = formula.isNotBlank() && value.isBlank() && type != "str" && type != "inlineStr"
            val (text, kind) = when {
                hasValue && !unsaved -> display(value.toString(), type, styleIndex)
                formula.isNotBlank() -> "=" + formula.toString().trim() to CellKind.TEXT
                else -> "" to CellKind.TEXT
            }
            // Empty cells are only kept when they're coloured in.
            if (text.isEmpty() && styles.getOrNull(styleIndex)?.fill == null) return
            if (cellCount >= maxCells) {
                endRow()
                throw StopParsing()
            }
            cellCount++
            rowCols += col
            rowTexts += text
            rowStyles += styleIndex
            rowKinds += kind
        }

        private fun endRow() {
            if (row < 0 || rowCols.isEmpty()) return
            val order = rowCols.indices.sortedBy { rowCols[it] }
            rows[row] = SheetRow(
                cols = IntArray(order.size) { rowCols[order[it]] },
                texts = Array(order.size) { rowTexts[order[it]] },
                styles = IntArray(order.size) { rowStyles[order[it]] },
                kinds = Array(order.size) { rowKinds[order[it]] },
            )
            maxRow = maxOf(maxRow, row)
            maxCol = maxOf(maxCol, rowCols.max())
            rowCols.clear()
            rowTexts.clear()
            rowStyles.clear()
            rowKinds.clear()
        }

        fun build(): Sheet {
            endRow()
            return Sheet(
                name = name,
                rowCount = maxRow + 1,
                colCount = maxCol + 1,
                defaultColWidth = defaultColWidth,
                defaultRowHeight = defaultRowHeight,
                colWidths = colWidths,
                rowHeights = rowHeights,
                rows = rows,
                merges = merges,
                styles = styles,
                truncated = truncated,
                frozenRows = frozenRows,
                frozenCols = frozenCols,
                showGrid = showGrid,
            )
        }
    }

    /** A cell's value as Excel would show it. */
    private fun display(raw: String, type: String, style: Int): Pair<String, CellKind> = when (type) {
        "s" -> (raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: "") to CellKind.TEXT
        "inlineStr", "str" -> unescape(raw) to CellKind.TEXT
        "b" -> (if (raw.trim() == "1" || raw.trim().equals("true", ignoreCase = true)) "TRUE" else "FALSE") to CellKind.BOOLEAN
        "e" -> raw.trim() to CellKind.ERROR
        "d" -> {
            val code = formats.getOrElse(style) { "General" }
            val serial = isoToSerial(raw.trim())
            if (serial != null && formatter.isDate(code)) formatter.format(serial, code) to CellKind.NUMBER else raw.trim() to CellKind.TEXT
        }
        else -> {
            val number = raw.trim().toDoubleOrNull()
            if (number == null) {
                raw.trim() to CellKind.TEXT
            } else {
                formatter.format(number, formats.getOrElse(style) { "General" }) to CellKind.NUMBER
            }
        }
    }

    private fun isoToSerial(text: String): Double? {
        val dateTime = runCatching { LocalDateTime.parse(text.removeSuffix("Z")) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
            ?: return null
        val base = LocalDate.of(1899, 12, 30).atStartOfDay()
        val seconds = ChronoUnit.SECONDS.between(base, dateTime)
        return seconds / 86_400.0
    }

    companion object {
        const val MAX_CELLS = 300_000
        private const val MAX_FROZEN = 50
        private const val MAX_COLUMNS = 16_384
        private const val MAX_STORED_COLUMN = 1_000
        private const val MAX_SHARED_STRINGS = 2_000_000
        private const val MAX_SHARED_STRING_BYTES = 128L * 1024 * 1024
        const val PX_PER_POINT = 96f / 72f
        const val DEFAULT_COL_WIDTH_PX = 64f
        const val DEFAULT_ROW_HEIGHT_PX = 20f

        /** Opens [file], reading its sheet list, shared text and styles. Throws [OfficeFileException]. */
        fun open(file: File, locale: Locale = Locale.getDefault()): Workbook {
            val pkg = OfficePackage(file)
            try {
                val main = pkg.mainPart()?.takeIf(pkg::has) ?: throw OfficeFileException(OfficePackage.NOT_OFFICE)
                val root = pkg.xml(main)
                val rels = pkg.relationshipMap(main)
                val date1904 = root.child("workbookPr")?.attr("date1904").isTrue()
                val sheets = root.child("sheets")?.children("sheet").orEmpty().mapNotNull { sheet ->
                    val state = sheet.attr("state")
                    if (state == "hidden" || state == "veryHidden") return@mapNotNull null
                    val rel = sheet.relAttr("id")?.let(rels::get) ?: return@mapNotNull null
                    // Chart sheets and old dialog sheets have no cells to show.
                    if (!rel.isType("worksheet") || rel.external || !pkg.has(rel.target)) return@mapNotNull null
                    SheetInfo(sheet.attr("name").orEmpty().ifEmpty { "Sheet" }, rel.target)
                }
                if (sheets.isEmpty()) throw OfficeFileException("This workbook has no sheets to show.")

                val sharedStrings = rels.values.firstOrNull { it.isType("sharedStrings") && !it.external && pkg.has(it.target) }
                    ?.let { readSharedStrings(pkg, it.target) }.orEmpty()
                val theme = rels.values.firstOrNull { it.isType("theme") && !it.external && pkg.has(it.target) }
                    ?.let { runCatching { ThemeColors.read(pkg.xml(it.target)) }.getOrNull() }
                val stylesNode = rels.values.firstOrNull { it.isType("styles") && !it.external && pkg.has(it.target) }?.let { pkg.xml(it.target) }
                val (styles, formats) = readStyles(stylesNode, theme)
                return Workbook(pkg, sheets, sharedStrings, styles, formats, NumberFormatter(locale, date1904))
            } catch (e: Exception) {
                pkg.close()
                throw e
            }
        }

        private fun readSharedStrings(pkg: OfficePackage, part: String): List<String> {
            val strings = ArrayList<String>()
            pkg.sax(
                part,
                object : DefaultHandler() {
                    val text = StringBuilder()
                    var inItem = false
                    var inText = false
                    var inPhonetic = false

                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                        when (localName) {
                            "si" -> {
                                inItem = true
                                text.setLength(0)
                            }
                            "rPh" -> inPhonetic = true
                            "t" -> if (inItem && !inPhonetic) inText = true
                        }
                    }

                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (inText) text.appendRange(ch, start, start + length)
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        when (localName) {
                            "t" -> inText = false
                            "rPh" -> inPhonetic = false
                            "si" -> {
                                inItem = false
                                if (strings.size >= MAX_SHARED_STRINGS) throw OfficeFileException("This workbook is too large to open.")
                                strings += unescape(text.toString())
                            }
                        }
                    }
                },
                MAX_SHARED_STRING_BYTES,
            )
            return strings
        }

        /** Cell styles and their number format codes, both indexed by a cell's `s` attribute. */
        private fun readStyles(root: XmlNode?, theme: ThemeColors?): Pair<List<CellStyle>, List<String>> {
            if (root == null) return listOf(CellStyle()) to listOf("General")
            val palette = root.path("colors", "indexedColors")?.children("rgbColor")?.mapNotNull { it.attr("rgb")?.let(::argb) }
                ?.takeIf { it.isNotEmpty() } ?: INDEXED_COLORS.toList()

            fun color(node: XmlNode?): Int? {
                node ?: return null
                if (node.attr("auto").isTrue()) return null
                val base = node.attr("rgb")?.let(::argb)
                    ?: node.attr("theme")?.toIntOrNull()?.let { theme?.excelIndex(it) }
                    ?: node.attr("indexed")?.toIntOrNull()?.let { if (it in 64..65) null else palette.getOrNull(it) }
                    ?: return null
                val tint = node.attr("tint")?.toDoubleOrNull() ?: 0.0
                return if (tint == 0.0) base else ThemeColors.tint(base, tint)
            }

            val numFmts = root.child("numFmts")?.children("numFmt").orEmpty().mapNotNull { n ->
                val id = n.attr("numFmtId")?.toIntOrNull() ?: return@mapNotNull null
                id to (n.attr("formatCode") ?: return@mapNotNull null)
            }.toMap()

            data class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float, val color: Int?)

            fun flag(node: XmlNode?, name: String): Boolean {
                val child = node?.child(name) ?: return false
                val v = child.attr("val") ?: return true
                return v.isTrue()
            }

            val fonts = root.child("fonts")?.children("font").orEmpty().map { f ->
                Font(
                    bold = flag(f, "b"),
                    italic = flag(f, "i"),
                    underline = f.child("u")?.let { it.attr("val") != "none" } ?: false,
                    strike = flag(f, "strike"),
                    size = f.child("sz")?.attr("val")?.toFloatOrNull()?.takeIf { it in 1f..409f } ?: 11f,
                    color = color(f.child("color")),
                )
            }

            val fills = root.child("fills")?.children("fill").orEmpty().map { fill ->
                fill.child("patternFill")?.let { p ->
                    when (p.attr("patternType")) {
                        null, "none" -> null
                        "solid" -> color(p.child("fgColor"))
                        // Hatched patterns: show the colour that dominates, which is close enough to read.
                        else -> color(p.child("fgColor")) ?: color(p.child("bgColor"))
                    }
                } ?: fill.child("gradientFill")?.children("stop")?.firstOrNull()?.let { color(it.child("color")) }
            }

            val styles = mutableListOf<CellStyle>()
            val formats = mutableListOf<String>()
            root.child("cellXfs")?.children("xf").orEmpty().forEach { xf ->
                val font = xf.attr("fontId")?.toIntOrNull()?.let(fonts::getOrNull)
                val fill = xf.attr("fillId")?.toIntOrNull()?.let(fills::getOrNull)
                val alignment = xf.child("alignment")
                val numFmtId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
                formats += numFmts[numFmtId] ?: NumberFormatter.builtIn[numFmtId] ?: "General"
                styles += CellStyle(
                    bold = font?.bold ?: false,
                    italic = font?.italic ?: false,
                    underline = font?.underline ?: false,
                    strike = font?.strike ?: false,
                    fontSize = font?.size ?: 11f,
                    textColor = font?.color,
                    fill = fill,
                    horizontal = when (alignment?.attr("horizontal")) {
                        "left" -> HAlign.LEFT
                        "center", "centerContinuous" -> HAlign.CENTER
                        "right" -> HAlign.RIGHT
                        "fill" -> HAlign.FILL
                        "justify", "distributed" -> HAlign.JUSTIFY
                        else -> HAlign.GENERAL
                    },
                    vertical = when (alignment?.attr("vertical")) {
                        "top" -> VAlign.TOP
                        "center", "justify", "distributed" -> VAlign.CENTER
                        else -> VAlign.BOTTOM
                    },
                    wrap = alignment?.attr("wrapText").isTrue(),
                    indent = alignment?.attr("indent")?.toIntOrNull()?.coerceIn(0, 250) ?: 0,
                )
            }
            if (styles.isEmpty()) {
                styles += CellStyle(fontSize = fonts.firstOrNull()?.size ?: 11f, textColor = fonts.firstOrNull()?.color)
                formats += "General"
            }
            return styles to formats
        }

        /** Excel's stored column width (in characters, padding included) to pixels. */
        fun columnPixels(width: Float): Float = floor(((256 * width + floor(128f / MAX_DIGIT_WIDTH)) / 256) * MAX_DIGIT_WIDTH).coerceAtLeast(0f)

        /** A default width given as characters without padding, rounded up to whole 8 pixel steps as Excel does. */
        private fun baseColumnPixels(chars: Float): Float = ceil((chars * MAX_DIGIT_WIDTH + 5) / 8f) * 8f

        private const val MAX_DIGIT_WIDTH = 7f

        /** "AB12" to its column, counted from 0. */
        fun columnOf(ref: String): Int {
            var col = 0
            for (c in ref.trimStart('$')) {
                if (c !in 'A'..'Z' && c !in 'a'..'z') break
                col = col * 26 + (c.uppercaseChar() - 'A' + 1)
            }
            return (col - 1).coerceIn(-1, MAX_COLUMNS - 1)
        }

        private fun rowOf(ref: String): Int = ref.dropWhile { it.isLetter() || it == '$' }.toIntOrNull()?.minus(1) ?: -1

        fun rangeOf(ref: String): CellRange? {
            val parts = ref.split(':')
            val a = parts[0]
            val b = parts.getOrElse(1) { a }
            val r1 = rowOf(a)
            val r2 = rowOf(b)
            val c1 = columnOf(a)
            val c2 = columnOf(b)
            if (r1 < 0 || r2 < 0 || c1 < 0 || c2 < 0) return null
            return CellRange(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
        }

        /** Column letters for [col] (0 is "A"). */
        fun columnName(col: Int): String {
            var n = col + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append('A' + rem)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        /** Excel writes control characters as _xHHHH_. */
        private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

        fun unescape(text: String): String = if ("_x" !in text) text else ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

        private fun argb(hex: String): Int? {
            val clean = hex.trim()
            val value = clean.toLongOrNull(16) ?: return null
            return when (clean.length) {
                8 -> (value or 0xFF000000).toInt() // Excel often writes alpha 00 but means opaque.
                6 -> (value or 0xFF000000).toInt()
                else -> null
            }
        }

        /** Excel's default 64-colour palette for indexed colours. */
        private val INDEXED_COLORS = intArrayOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
            0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
            0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
            0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
            0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
            0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
        ).map { it or 0xFF000000.toInt() }
    }
}

private fun String?.isTrue() = this == "1" || this.equals("true", ignoreCase = true)

private fun String?.isFalse() = this == "0" || this.equals("false", ignoreCase = true)
