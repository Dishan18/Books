package io.github.dishan18.books.office.slides

import io.github.dishan18.books.office.OfficeFileException
import io.github.dishan18.books.office.OfficePackage
import io.github.dishan18.books.office.Relationship
import io.github.dishan18.books.office.XmlNode
import io.github.dishan18.books.office.drawing.ColorResolver
import io.github.dishan18.books.office.drawing.ThemeColors

/**
 * Reads a PowerPoint file into a [Presentation]. Slides inherit from their layout, which inherits
 * from its master and theme; this resolves all of that, so drawing needs nothing but the result.
 */
class PresentationReader(private val pkg: OfficePackage) {

    private class Part(val path: String, val root: XmlNode, val rels: Map<String, Relationship>)

    private class Master(
        val part: Part,
        val theme: ThemeColors,
        val clrMap: Map<String, String>,
        val placeholders: List<XmlNode>,
        val titleStyle: XmlNode?,
        val bodyStyle: XmlNode?,
        val otherStyle: XmlNode?,
    )

    private class Layout(val part: Part, val master: Master?, val clrMapOverride: Map<String, String>?, val placeholders: List<XmlNode>)

    private enum class Level { SLIDE, LAYOUT, MASTER }

    private class Ctx(
        val colors: ColorResolver,
        val theme: ThemeColors,
        val master: Master?,
        val layout: Layout?,
        val rels: Map<String, Relationship>,
        val level: Level,
        val slideNumber: Int,
    ) {
        fun withRels(rels: Map<String, Relationship>) = Ctx(colors, theme, master, layout, rels, level, slideNumber)
    }

    /** Text colour, weight and font a shape's style or a table's style gives text that doesn't set its own. */
    private data class TextDefaults(val color: Int? = null, val bold: Boolean? = null, val italic: Boolean? = null, val font: String? = null)

    private val masters = HashMap<String, Master?>()
    private val layouts = HashMap<String, Layout?>()
    private var defaultTextStyle: XmlNode? = null
    private var tableStyles: Map<String, XmlNode> = emptyMap()

    fun read(): Presentation {
        val main = pkg.mainPart()?.takeIf(pkg::has) ?: throw OfficeFileException(OfficePackage.NOT_OFFICE)
        val root = pkg.xml(main)
        val rels = pkg.relationshipMap(main)
        val size = root.child("sldSz")
        val width = size?.attr("cx")?.toFloatOrNull()?.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = size?.attr("cy")?.toFloatOrNull()?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        defaultTextStyle = root.child("defaultTextStyle")
        tableStyles = rels.values.firstOrNull { it.isType("tableStyles") && !it.external && pkg.has(it.target) }
            ?.let { runCatching { pkg.xml(it.target) }.getOrNull() }
            ?.children("tblStyle")?.mapNotNull { s -> s.attr("styleId")?.let { it to s } }?.toMap().orEmpty()

        val slideParts = root.child("sldIdLst")?.children("sldId").orEmpty().mapNotNull { sld ->
            sld.relAttr("id")?.let(rels::get)?.takeIf { it.isType("slide") && !it.external && pkg.has(it.target) }?.target
        }.take(MAX_SLIDES)
        if (slideParts.isEmpty()) throw OfficeFileException("This presentation has no slides.")
        val slides = slideParts.mapIndexed { i, path ->
            try {
                readSlide(path, i + 1)
            } catch (e: OfficeFileException) {
                throw e
            } catch (e: Exception) {
                // One damaged slide shouldn't hide the rest of the deck.
                Slide(i + 1, Fill.Solid(WHITE), listOf(Shape.Unsupported(Frame(0f, 0f, width, height), "This slide couldn't be shown")))
            }
        }
        return Presentation(width, height, slides)
    }

    // Parts

    private fun part(path: String) = Part(path, pkg.xml(path), pkg.relationshipMap(path))

    private fun master(path: String): Master? = masters.getOrPut(path) {
        runCatching {
            val p = part(path)
            val theme = p.rels.values.firstOrNull { it.isType("theme") && !it.external && pkg.has(it.target) }
                ?.let { ThemeColors.read(pkg.xml(it.target)) } ?: ThemeColors.DEFAULT
            val clrMap = ColorResolver.clrMapOf(p.root.child("clrMap")).ifEmpty { ColorResolver.DEFAULT_MAP }
            val txStyles = p.root.child("txStyles")
            Master(p, theme, clrMap, placeholdersOf(p.root), txStyles?.child("titleStyle"), txStyles?.child("bodyStyle"), txStyles?.child("otherStyle"))
        }.getOrNull()
    }

    private fun layout(path: String): Layout? = layouts.getOrPut(path) {
        runCatching {
            val p = part(path)
            val master = p.rels.values.firstOrNull { it.isType("slideMaster") && !it.external && pkg.has(it.target) }?.target?.let(::master)
            Layout(p, master, clrMapOverride(p.root), placeholdersOf(p.root))
        }.getOrNull()
    }

    private fun clrMapOverride(root: XmlNode): Map<String, String>? =
        root.path("clrMapOvr", "overrideClrMapping")?.let(ColorResolver::clrMapOf)

    /** Placeholder shapes in a layout or master, wherever they sit in its shape tree. */
    private fun placeholdersOf(root: XmlNode): List<XmlNode> {
        val found = mutableListOf<XmlNode>()
        fun walk(node: XmlNode) {
            node.children.forEach { child ->
                when (child.name) {
                    "sp", "pic", "graphicFrame" -> if (phOf(child) != null) found += child
                    "grpSp" -> walk(child)
                    "AlternateContent" -> (child.child("Fallback") ?: child.child("Choice"))?.let(::walk)
                }
            }
        }
        root.path("cSld", "spTree")?.let(::walk)
        return found
    }

    // Slides

    private fun readSlide(path: String, number: Int): Slide {
        val slide = part(path)
        val layout = slide.rels.values.firstOrNull { it.isType("slideLayout") && !it.external && pkg.has(it.target) }?.target?.let(::layout)
        val master = layout?.master
        val theme = master?.theme ?: ThemeColors.DEFAULT
        var clrMap = master?.clrMap ?: ColorResolver.DEFAULT_MAP
        layout?.clrMapOverride?.let { clrMap = clrMap + it }
        clrMapOverride(slide.root)?.let { clrMap = clrMap + it }
        val colors = ColorResolver(theme, clrMap)

        fun ctx(part: Part, level: Level) = Ctx(colors, theme, master, layout, part.rels, level, number)

        val backgroundSource = listOfNotNull(slide, layout?.part, master?.part).firstOrNull { it.root.path("cSld", "bg") != null }
        val background = backgroundSource?.let { background(it.root.path("cSld", "bg"), ctx(it, Level.SLIDE)) } ?: Fill.Solid(WHITE)

        val showMaster = !slide.root.attr("showMasterSp").isFalse()
        val layoutShowsMaster = !(layout?.part?.root?.attr("showMasterSp").isFalse())
        val shapes = mutableListOf<Shape>()
        if (showMaster && layoutShowsMaster && master != null) shapes += tree(master.part.root.path("cSld", "spTree"), ctx(master.part, Level.MASTER))
        if (showMaster && layout != null) shapes += tree(layout.part.root.path("cSld", "spTree"), ctx(layout.part, Level.LAYOUT))
        shapes += tree(slide.root.path("cSld", "spTree"), ctx(slide, Level.SLIDE))
        return Slide(number, background, shapes)
    }

    private fun tree(node: XmlNode?, ctx: Ctx): List<Shape> = node?.children.orEmpty().flatMap { element(it, ctx) }

    private fun element(node: XmlNode, ctx: Ctx): List<Shape> {
        if (isHidden(node)) return emptyList()
        return when (node.name) {
            "sp", "cxnSp" -> listOfNotNull(shape(node, ctx))
            "pic" -> listOfNotNull(picture(node, ctx))
            "grpSp" -> group(node, ctx)
            "graphicFrame" -> graphicFrame(node, ctx)
            // Newer features come with an older-format fallback that any reader can draw.
            "AlternateContent" -> (node.child("Fallback") ?: node.child("Choice"))?.children.orEmpty().flatMap { element(it, ctx) }
            else -> emptyList()
        }
    }

    private fun nonVisual(node: XmlNode): XmlNode? = node.children.firstOrNull { it.name.startsWith("nv") }

    private fun isHidden(node: XmlNode) = nonVisual(node)?.child("cNvPr")?.attr("hidden").isTrue()

    private fun phOf(node: XmlNode): XmlNode? = nonVisual(node)?.child("nvPr")?.child("ph")

    // Placeholders

    private fun phType(ph: XmlNode) = ph.attr("type") ?: "obj"

    private fun masterTypeOf(type: String) = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> type
        else -> "body"
    }

    private fun compatible(a: String, b: String) = a == b || masterTypeOf(a) == masterTypeOf(b) && masterTypeOf(a) in setOf("title", "body")

    /** The layout and master shapes a slide placeholder inherits from, nearest first. */
    private fun inherited(ph: XmlNode, ctx: Ctx): List<XmlNode> {
        val type = phType(ph)
        val idx = ph.attr("idx")
        val layoutShape = ctx.layout?.placeholders?.let { list ->
            list.firstOrNull { s -> idx != null && phOf(s)?.attr("idx") == idx && compatible(phType(phOf(s)!!), type) }
                ?: list.firstOrNull { s -> idx != null && phOf(s)?.attr("idx") == idx }
                ?: list.firstOrNull { s -> phType(phOf(s)!!) == type }
                ?: list.firstOrNull { s -> compatible(phType(phOf(s)!!), type) }
        }
        val masterType = masterTypeOf(layoutShape?.let { phType(phOf(it)!!) } ?: type)
        val masterShape = ctx.master?.placeholders?.firstOrNull { s -> masterTypeOf(phType(phOf(s)!!)) == masterType }
        return listOfNotNull(layoutShape, masterShape)
    }

    private fun masterTextStyle(ph: XmlNode?, inherited: List<XmlNode>, ctx: Ctx): XmlNode? {
        val master = ctx.master ?: return null
        if (ph == null) return master.otherStyle
        val type = inherited.firstOrNull()?.let { phOf(it)?.let(::phType) } ?: phType(ph)
        return when (masterTypeOf(type)) {
            "title" -> master.titleStyle
            "body" -> master.bodyStyle
            else -> master.otherStyle
        }
    }

    // Shapes

    private fun shape(node: XmlNode, ctx: Ctx): Shape? {
        val ph = phOf(node)
        // Placeholders on layouts and masters are prompts ("Click to add title"), never shown.
        if (ph != null && ctx.level != Level.SLIDE) return null
        val inherited = if (ph != null) inherited(ph, ctx) else emptyList()
        val spPr = node.child("spPr")
        val box = frame(spPr?.child("xfrm")) ?: inherited.firstNotNullOfOrNull { frame(it.child("spPr")?.child("xfrm")) } ?: return null
        val shapeGeometry = geometry(spPr) ?: inherited.firstNotNullOfOrNull { geometry(it.child("spPr")) } ?: Geometry.Preset("rect")
        val style = node.child("style")
        val shapeFill = fill(spPr, ctx) ?: inherited.firstNotNullOfOrNull { fill(it.child("spPr"), ctx) } ?: styleFill(style, ctx)
        val outline = stroke(listOf(spPr?.child("ln")) + inherited.map { it.child("spPr")?.child("ln") }, style, ctx)

        val text = node.child("txBody")?.let { body ->
            val fontRef = style?.child("fontRef")
            val defaults = TextDefaults(
                color = fontRef?.let { ctx.colors.color(it) },
                font = when (fontRef?.attr("idx")) {
                    "major" -> ctx.theme.majorFont
                    "minor" -> ctx.theme.minorFont
                    else -> null
                },
            )
            val levels = listOf(body.child("lstStyle")) + inherited.map { it.child("txBody")?.child("lstStyle") } +
                listOf(masterTextStyle(ph, inherited, ctx), if (ph == null) defaultTextStyle else null)
            val bodyPrs = listOf(body.child("bodyPr")) + inherited.map { it.child("txBody")?.child("bodyPr") }
            textBody(body, levels, bodyPrs, defaults, ctx, frame(node.child("txXfrm")))
        }
        return Shape.Box(box, shapeGeometry, shapeFill.takeUnless { it == Fill.None }, outline, text)
    }

    private fun picture(node: XmlNode, ctx: Ctx, frameOverride: Frame? = null): Shape? {
        val ph = phOf(node)
        if (ph != null && ctx.level != Level.SLIDE) return null
        val inherited = if (ph != null) inherited(ph, ctx) else emptyList()
        val spPr = node.child("spPr")
        val box = frameOverride ?: frame(spPr?.child("xfrm")) ?: inherited.firstNotNullOfOrNull { frame(it.child("spPr")?.child("xfrm")) } ?: return null
        val blipFill = node.child("blipFill")
        // Linked (external) pictures are never fetched; only pictures inside the file are drawn.
        val part = blipFill?.child("blip")?.relAttr("embed")?.let(ctx.rels::get)?.takeIf { !it.external && pkg.has(it.target) }?.target
        val src = blipFill?.child("srcRect")
        fun pct(name: String) = (src?.attr(name)?.toFloatOrNull() ?: 0f) / 100_000f
        return Shape.Picture(
            frame = box,
            part = part,
            geometry = geometry(spPr) ?: Geometry.Preset("rect"),
            crop = floatArrayOf(pct("l"), pct("t"), pct("r"), pct("b")),
            stroke = stroke(listOf(spPr?.child("ln")), node.child("style"), ctx),
        )
    }

    private fun group(node: XmlNode, ctx: Ctx): List<Shape> {
        val children = node.children.flatMap { element(it, ctx) }
        val xfrm = node.child("grpSpPr")?.child("xfrm") ?: return children
        val box = frame(xfrm) ?: return children
        val chOff = xfrm.child("chOff")
        val chExt = xfrm.child("chExt")
        return listOf(
            Shape.Group(
                frame = box,
                childX = chOff?.attr("x")?.toFloatOrNull() ?: box.x,
                childY = chOff?.attr("y")?.toFloatOrNull() ?: box.y,
                childWidth = chExt?.attr("cx")?.toFloatOrNull()?.takeIf { it > 0 } ?: box.width,
                childHeight = chExt?.attr("cy")?.toFloatOrNull()?.takeIf { it > 0 } ?: box.height,
                children = children,
            ),
        )
    }

    private fun graphicFrame(node: XmlNode, ctx: Ctx): List<Shape> {
        val ph = phOf(node)
        if (ph != null && ctx.level != Level.SLIDE) return emptyList()
        val box = frame(node.child("xfrm"))
            ?: ph?.let { inherited(it, ctx).firstNotNullOfOrNull { s -> frame(s.child("spPr")?.child("xfrm")) } }
            ?: return emptyList()
        val data = node.path("graphic", "graphicData") ?: return emptyList()
        val uri = data.attr("uri").orEmpty()
        return when {
            uri.endsWith("/table") -> listOfNotNull(data.child("tbl")?.let { table(it, box, ctx) })
            uri.endsWith("/chart") || uri.endsWith("/chartex") -> listOf(Shape.Unsupported(box, "Chart"))
            uri.endsWith("/diagram") -> listOf(smartArt(data, box, ctx) ?: Shape.Unsupported(box, "Diagram"))
            else -> {
                // Embedded objects (a pasted spreadsheet, an equation) usually carry a picture of themselves.
                val pic = descendant(data, "pic")?.let { picture(it, ctx, box) }
                listOf(pic ?: Shape.Unsupported(box, "Embedded object"))
            }
        }
    }

    private fun descendant(node: XmlNode, name: String): XmlNode? {
        node.children.forEach { child ->
            if (child.name == name) return child
            descendant(child, name)?.let { return it }
        }
        return null
    }

    /** SmartArt: PowerPoint saves a ready-drawn copy of the diagram's shapes, which is what gets shown. */
    private fun smartArt(data: XmlNode, frame: Frame, ctx: Ctx): Shape? = runCatching {
        val dataPart = data.child("relIds")?.relAttr("dm")?.let(ctx.rels::get)?.takeIf { !it.external && pkg.has(it.target) }?.target
        val drawingRelId = dataPart?.let { descendant(pkg.xml(it), "dataModelExt")?.attr("relId") }
        val drawing = (drawingRelId?.let(ctx.rels::get) ?: ctx.rels.values.singleOrNull { it.isType("diagramDrawing") })
            ?.takeIf { !it.external && pkg.has(it.target) }?.target ?: return@runCatching null
        val root = pkg.xml(drawing)
        val children = tree(root.child("spTree"), ctx.withRels(pkg.relationshipMap(drawing)))
        // The drawing's shapes are placed relative to the diagram's frame.
        Shape.Group(frame, 0f, 0f, frame.width, frame.height, children)
    }.getOrNull()

    // Tables

    private fun table(tbl: XmlNode, frame: Frame, ctx: Ctx): Shape.Table {
        val tblPr = tbl.child("tblPr")
        val firstRow = tblPr?.attr("firstRow").isTrue()
        val lastRow = tblPr?.attr("lastRow").isTrue()
        val firstCol = tblPr?.attr("firstCol").isTrue()
        val lastCol = tblPr?.attr("lastCol").isTrue()
        val bandRow = tblPr?.attr("bandRow").isTrue()
        val bandCol = tblPr?.attr("bandCol").isTrue()
        val styleId = tblPr?.child("tableStyleId")?.text?.trim()
        val style = styleId?.let(tableStyles::get)
        val columns = tbl.child("tblGrid")?.children("gridCol").orEmpty().map { it.attr("w")?.toFloatOrNull() ?: 0f }
        val rowNodes = tbl.children("tr")

        val rows = rowNodes.mapIndexed { r, tr ->
            val cells = tr.children("tc").mapIndexed { c, tc ->
                val parts = buildList {
                    add("wholeTbl")
                    val dataRow = r - if (firstRow) 1 else 0
                    if (bandCol) add(if (c % 2 == 0) "band1V" else "band2V")
                    if (bandRow && dataRow >= 0) add(if (dataRow % 2 == 0) "band1H" else "band2H")
                    if (firstCol && c == 0) add("firstCol")
                    if (lastCol && c == tr.children("tc").lastIndex) add("lastCol")
                    if (lastRow && r == rowNodes.lastIndex) add("lastRow")
                    if (firstRow && r == 0) add("firstRow")
                }
                val outer = Edges(left = c == 0, right = c == tr.children("tc").lastIndex, top = r == 0, bottom = r == rowNodes.lastIndex)
                cell(tc, parts, style, styleId, outer, ctx)
            }
            TableRow(tr.attr("h")?.toFloatOrNull() ?: 0f, cells)
        }
        return Shape.Table(frame, columns, rows)
    }

    private data class CellLook(val fill: Fill?, val color: Int?, val bold: Boolean?, val italic: Boolean?, val borders: Map<String, Stroke?>)

    /** What a table style gives a cell, from its parts (whole table, banded rows, header row...), later parts winning. */
    private fun tableStyleLook(parts: List<String>, style: XmlNode?, styleId: String?, ctx: Ctx): CellLook {
        if (style == null) return builtInLook(parts, styleId, ctx)
        var cellFill: Fill? = null
        var color: Int? = null
        var bold: Boolean? = null
        var italic: Boolean? = null
        val borders = HashMap<String, Stroke?>()
        parts.forEach { name ->
            val part = style.child(name) ?: return@forEach
            part.child("tcTxStyle")?.let { tx ->
                tx.attr("b")?.let { bold = it == "on" }
                tx.attr("i")?.let { italic = it == "on" }
                (ctx.colors.color(tx) ?: tx.child("fontRef")?.let { ctx.colors.color(it) })?.let { color = it }
            }
            part.child("tcStyle")?.let { tc ->
                (fill(tc.child("fill"), ctx) ?: tc.child("fillRef")?.let { styleFill(wrap("style", it), ctx) })?.let { cellFill = it }
                tc.child("tcBdr")?.children?.forEach { edge ->
                    val ln = edge.child("ln")
                    borders[edge.name] = if (ln != null) stroke(listOf(ln), null, ctx) else edge.child("lnRef")?.let { stroke(emptyList(), wrap("style", it), ctx) }
                }
            }
        }
        return CellLook(cellFill, color, bold, italic, borders)
    }

    /** PowerPoint's default table look (Medium Style 2, first accent) for styles the file doesn't define. */
    private fun builtInLook(parts: List<String>, styleId: String?, ctx: Ctx): CellLook {
        val dark = ctx.colors.scheme("dk1") ?: BLACK
        val light = ctx.colors.scheme("lt1") ?: WHITE
        if (styleId == NO_STYLE_ID) return CellLook(null, dark, null, null, emptyMap())
        if (styleId == NO_STYLE_GRID_ID) {
            val line = Stroke(12_700f, dark)
            return CellLook(null, dark, null, null, mapOf("left" to line, "right" to line, "top" to line, "bottom" to line, "insideH" to line, "insideV" to line))
        }
        val accent = ctx.colors.scheme("accent1") ?: 0xFF4472C4.toInt()
        val white = Stroke(12_700f, light)
        val edges = mapOf("left" to white, "right" to white, "top" to white, "bottom" to white, "insideH" to white, "insideV" to white)
        val header = "firstRow" in parts || "lastRow" in parts
        val column = "firstCol" in parts || "lastCol" in parts
        return when {
            header -> CellLook(Fill.Solid(accent), light, true, null, edges)
            column -> CellLook(Fill.Solid(accent), light, true, null, edges)
            "band1H" in parts || "band1V" in parts -> CellLook(Fill.Solid(blend(accent, light, 0.4f)), dark, null, null, edges)
            else -> CellLook(Fill.Solid(blend(accent, light, 0.2f)), dark, null, null, edges)
        }
    }

    /** Which sides of a cell are the table's outside edges. */
    private data class Edges(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean)

    private fun cell(tc: XmlNode, parts: List<String>, style: XmlNode?, styleId: String?, outer: Edges, ctx: Ctx): TableCell {
        val look = tableStyleLook(parts, style, styleId, ctx)
        val tcPr = tc.child("tcPr")
        val ownFill = fill(tcPr, ctx)
        // The cell's own line wins, even "no line"; otherwise the style's outer or inside line, by position.
        fun edge(own: String, outerName: String, inside: String, isOuter: Boolean): Stroke? {
            val line = tcPr?.child(own)
            if (line != null) return stroke(listOf(line), null, ctx)
            return look.borders[if (isOuter) outerName else inside]
        }
        val text = tc.child("txBody")?.let { body ->
            val defaults = TextDefaults(color = look.color, bold = look.bold, italic = look.italic)
            val levels = listOf(body.child("lstStyle"), ctx.master?.otherStyle, defaultTextStyle)
            // Table cells keep their margins and alignment on the cell, not the text.
            val margins = XmlNode(
                "bodyPr",
                mapOf(
                    "lIns" to (tcPr?.attr("marL") ?: "91440"),
                    "rIns" to (tcPr?.attr("marR") ?: "91440"),
                    "tIns" to (tcPr?.attr("marT") ?: "45720"),
                    "bIns" to (tcPr?.attr("marB") ?: "45720"),
                    "anchor" to (tcPr?.attr("anchor") ?: "t"),
                ),
            )
            textBody(body, levels, listOf(margins), defaults, ctx, null)
        }
        return TableCell(
            text = text,
            fill = (ownFill ?: look.fill)?.takeUnless { it == Fill.None },
            gridSpan = tc.attr("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            rowSpan = tc.attr("rowSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            merged = tc.attr("hMerge").isTrue() || tc.attr("vMerge").isTrue(),
            left = edge("lnL", "left", "insideV", outer.left),
            right = edge("lnR", "right", "insideV", outer.right),
            top = edge("lnT", "top", "insideH", outer.top),
            bottom = edge("lnB", "bottom", "insideH", outer.bottom),
        )
    }

    // Text

    private fun textBody(
        body: XmlNode,
        levels: List<XmlNode?>,
        bodyPrs: List<XmlNode?>,
        defaults: TextDefaults,
        ctx: Ctx,
        frame: Frame?,
    ): TextBody {
        fun bodyAttr(name: String) = bodyPrs.firstNotNullOfOrNull { it?.attr(name) }
        val own = body.child("bodyPr")
        val autofit = own?.child("normAutofit")
        val counters = IntArray(9)
        val counting = BooleanArray(9)
        val paragraphs = body.children("p").map { p ->
            paragraph(p, levels, defaults, ctx, counters, counting)
        }
        return TextBody(
            insetLeft = bodyAttr("lIns")?.toFloatOrNull() ?: 91_440f,
            insetTop = bodyAttr("tIns")?.toFloatOrNull() ?: 45_720f,
            insetRight = bodyAttr("rIns")?.toFloatOrNull() ?: 91_440f,
            insetBottom = bodyAttr("bIns")?.toFloatOrNull() ?: 45_720f,
            anchor = when (bodyAttr("anchor")) {
                "ctr" -> TextAnchor.MIDDLE
                "b" -> TextAnchor.BOTTOM
                else -> TextAnchor.TOP
            },
            anchorCenter = bodyAttr("anchorCtr").isTrue(),
            wrap = bodyAttr("wrap") != "none",
            vertical = when (bodyAttr("vert")) {
                "vert", "eaVert", "wordArtVert", "mongolianVert", "wordArtVertRtl" -> 90
                "vert270" -> 270
                else -> 0
            },
            fontScale = (autofit?.attr("fontScale")?.toFloatOrNull() ?: 100_000f) / 100_000f,
            lineReduction = (autofit?.attr("lnSpcReduction")?.toFloatOrNull() ?: 0f) / 100_000f,
            paragraphs = paragraphs,
            frame = frame,
        )
    }

    private fun paragraph(
        p: XmlNode,
        levels: List<XmlNode?>,
        defaults: TextDefaults,
        ctx: Ctx,
        counters: IntArray,
        counting: BooleanArray,
    ): Paragraph {
        val pPr = p.child("pPr")
        val level = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
        val levelName = "lvl${level + 1}pPr"
        val ownLevel = levels.firstOrNull()?.child(levelName)
        val restLevels = levels.drop(1).mapNotNull { it?.child(levelName) }
        // Also fall back on a list's general paragraph defaults.
        val levelNodes = listOfNotNull(ownLevel) + restLevels + levels.mapNotNull { it?.child("defPPr") }
        val pSources = listOfNotNull(pPr) + levelNodes

        fun pAttr(name: String) = pSources.firstNotNullOfOrNull { it.attr(name) }
        fun pChild(name: String) = pSources.firstNotNullOfOrNull { it.child(name) }

        val runSources = RunSources(listOfNotNull(ownLevel?.child("defRPr")), defaults, restLevels.mapNotNull { it.child("defRPr") } +
            levels.mapNotNull { it?.child("defPPr")?.child("defRPr") })

        val runs = mutableListOf<TextRun>()
        p.children.forEach { child ->
            when (child.name) {
                "r" -> runs += run(child.child("t")?.text.orEmpty(), child.child("rPr"), runSources, ctx)
                "br" -> runs += run("\n", child.child("rPr"), runSources, ctx)
                "fld" -> {
                    val text = if (child.attr("type") == "slidenum") ctx.slideNumber.toString() else child.child("t")?.text.orEmpty()
                    runs += run(text, child.child("rPr"), runSources, ctx)
                }
            }
        }
        val endSize = sizeOf(p.child("endParaRPr"), runSources)
        val firstRun = runs.firstOrNull { it.text.isNotBlank() }

        // Bullets: the nearest source that says anything about them decides. Empty lines get none
        // and don't interrupt a numbered list.
        val bulletSource = pSources.firstOrNull { s -> BULLET_KINDS.any { s.child(it) != null } }
        val bullet = when {
            firstRun == null -> null
            bulletSource == null || bulletSource.child("buNone") != null -> null
            else -> {
                val bulletText = when {
                    bulletSource.child("buChar") != null -> bulletSource.child("buChar")?.attr("char")?.takeIf { it.isNotEmpty() } ?: "•"
                    bulletSource.child("buAutoNum") != null -> {
                        val auto = bulletSource.child("buAutoNum")!!
                        val start = auto.attr("startAt")?.toIntOrNull() ?: 1
                        if (!counting[level]) counters[level] = start - 1
                        counters[level]++
                        counting[level] = true
                        autoNumber(auto.attr("type") ?: "arabicPeriod", counters[level])
                    }
                    else -> "•"
                }
                val colorSource = pSources.firstOrNull { it.child("buClr") != null || it.child("buClrTx") != null }
                val sizeSource = pSources.firstOrNull { it.child("buSzPct") != null || it.child("buSzPts") != null || it.child("buSzTx") != null }
                val fontSource = pSources.firstOrNull { it.child("buFont") != null || it.child("buFontTx") != null }
                val runSize = firstRun?.size ?: endSize
                Bullet(
                    text = bulletText,
                    color = colorSource?.child("buClr")?.let { ctx.colors.color(it) },
                    sizeRatio = sizeSource?.let { s ->
                        s.child("buSzPct")?.attr("val")?.toFloatOrNull()?.div(100_000f)
                            ?: s.child("buSzPts")?.attr("val")?.toFloatOrNull()?.div(100f)?.div(runSize.coerceAtLeast(1f))
                    }?.coerceIn(0.25f, 4f) ?: 1f,
                    font = fontSource?.child("buFont")?.attr("typeface"),
                )
            }
        }
        // A numbered list restarts after a line at this level that isn't part of it; deeper lists restart too.
        if (firstRun != null) {
            if (bullet == null || bulletSource?.child("buAutoNum") == null) counting[level] = false
            for (deeper in level + 1 until counting.size) counting[deeper] = false
        }

        return Paragraph(
            align = when (pAttr("algn")) {
                "ctr" -> Align.CENTER
                "r" -> Align.RIGHT
                "just", "dist", "justLow", "thaiDist" -> Align.JUSTIFY
                else -> Align.LEFT
            },
            marginLeft = pAttr("marL")?.toFloatOrNull() ?: 0f,
            indent = pAttr("indent")?.toFloatOrNull() ?: 0f,
            spaceBefore = spacing(pChild("spcBef")) ?: Spacing.Points(0f),
            spaceAfter = spacing(pChild("spcAft")) ?: Spacing.Points(0f),
            lineSpacing = spacing(pChild("lnSpc")) ?: Spacing.Percent(1f),
            bullet = bullet,
            runs = runs,
            endSize = endSize,
        )
    }

    private fun spacing(node: XmlNode?): Spacing? {
        node ?: return null
        node.child("spcPts")?.attr("val")?.toFloatOrNull()?.let { return Spacing.Points(it / 100f) }
        node.child("spcPct")?.attr("val")?.toFloatOrNull()?.let { return Spacing.Percent(it / 100_000f) }
        return null
    }

    /** Where a run's properties come from when it doesn't set them: its list level, then the shape's style, then the rest. */
    private class RunSources(val own: List<XmlNode>, val defaults: TextDefaults, val rest: List<XmlNode>) {
        fun attr(rPr: XmlNode?, name: String): String? = rPr?.attr(name) ?: own.firstNotNullOfOrNull { it.attr(name) }
        fun restAttr(name: String): String? = rest.firstNotNullOfOrNull { it.attr(name) }
        fun child(rPr: XmlNode?, names: Collection<String>): XmlNode? =
            rPr?.children?.firstOrNull { it.name in names } ?: own.firstNotNullOfOrNull { s -> s.children.firstOrNull { it.name in names } }
        fun restChild(names: Collection<String>): XmlNode? = rest.firstNotNullOfOrNull { s -> s.children.firstOrNull { it.name in names } }
    }

    private fun sizeOf(rPr: XmlNode?, sources: RunSources): Float =
        (sources.attr(rPr, "sz") ?: sources.restAttr("sz"))?.toFloatOrNull()?.div(100f)?.takeIf { it in 1f..4000f } ?: 18f

    private fun run(text: String, rPr: XmlNode?, sources: RunSources, ctx: Ctx): TextRun {
        fun flag(name: String, default: Boolean?): Boolean =
            sources.attr(rPr, name)?.isTrue() ?: default ?: sources.restAttr(name)?.isTrue() ?: false

        val link = rPr?.child("hlinkClick") != null
        val fillNode = sources.child(rPr, FILL_NAMES) ?: (if (sources.defaults.color != null) null else sources.restChild(FILL_NAMES))
        val color = when {
            rPr?.children?.any { it.name in FILL_NAMES } != true && link -> ctx.colors.scheme("hlink")
            fillNode != null -> fillColor(fillNode, ctx)
            else -> sources.defaults.color
        } ?: ctx.colors.scheme("tx1") ?: BLACK

        val latin = (sources.child(rPr, LATIN) ?: sources.restChild(LATIN))?.attr("typeface")
        val font = when {
            latin == null || latin.isEmpty() -> sources.defaults.font ?: ctx.theme.minorFont
            latin.startsWith("+mj") -> ctx.theme.majorFont
            latin.startsWith("+mn") -> ctx.theme.minorFont
            else -> latin
        } ?: "Calibri"

        val caps = sources.attr(rPr, "cap") ?: sources.restAttr("cap")
        val underline = (sources.attr(rPr, "u") ?: sources.restAttr("u"))?.let { it != "none" } ?: link
        val strike = (sources.attr(rPr, "strike") ?: sources.restAttr("strike"))?.let { it != "noStrike" } ?: false
        return TextRun(
            text = if (caps == "all" || caps == "small") text.uppercase() else text,
            size = sizeOf(rPr, sources),
            bold = flag("b", sources.defaults.bold),
            italic = flag("i", sources.defaults.italic),
            underline = underline,
            strike = strike,
            color = color,
            font = font,
            baseline = (sources.attr(rPr, "baseline") ?: sources.restAttr("baseline"))?.toFloatOrNull()?.div(100_000f) ?: 0f,
            highlight = rPr?.child("highlight")?.let { ctx.colors.color(it) },
        )
    }

    private fun fillColor(node: XmlNode, ctx: Ctx): Int? = when (node.name) {
        "solidFill" -> ctx.colors.color(node)
        "gradFill" -> node.child("gsLst")?.children("gs")?.firstOrNull()?.let { ctx.colors.color(it) }
        "noFill" -> 0x00000000
        else -> null
    }

    private fun autoNumber(type: String, n: Int): String {
        val body = when {
            type.startsWith("alphaLc") -> alpha(n).lowercase()
            type.startsWith("alphaUc") -> alpha(n)
            type.startsWith("romanLc") -> roman(n).lowercase()
            type.startsWith("romanUc") -> roman(n)
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($body)"
            type.endsWith("ParenR") -> "$body)"
            type.endsWith("Period") -> "$body."
            type.endsWith("Minus") -> "- $body -"
            else -> body
        }
    }

    private fun alpha(n: Int): String {
        var k = n.coerceAtLeast(1)
        val sb = StringBuilder()
        while (k > 0) {
            val rem = (k - 1) % 26
            sb.append('A' + rem)
            k = (k - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun roman(n: Int): String {
        var k = n.coerceIn(1, 3999)
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        values.forEachIndexed { i, v ->
            while (k >= v) {
                sb.append(symbols[i])
                k -= v
            }
        }
        return sb.toString()
    }

    // Geometry, fills and lines

    private fun frame(xfrm: XmlNode?): Frame? {
        xfrm ?: return null
        val off = xfrm.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        return Frame(
            x = off.attr("x")?.toFloatOrNull() ?: 0f,
            y = off.attr("y")?.toFloatOrNull() ?: 0f,
            width = ext.attr("cx")?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f,
            height = ext.attr("cy")?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f,
            rotation = (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60_000f,
            flipH = xfrm.attr("flipH").isTrue(),
            flipV = xfrm.attr("flipV").isTrue(),
        )
    }

    private fun geometry(spPr: XmlNode?): Geometry? {
        spPr ?: return null
        spPr.child("prstGeom")?.let { p ->
            val adjust = p.child("avLst")?.children("gd").orEmpty().mapNotNull { gd ->
                val name = gd.attr("name") ?: return@mapNotNull null
                val value = gd.attr("fmla")?.removePrefix("val")?.trim()?.toFloatOrNull() ?: return@mapNotNull null
                name to value
            }.toMap()
            return Geometry.Preset(p.attr("prst") ?: "rect", adjust)
        }
        spPr.child("custGeom")?.let { return custom(it) }
        return null
    }

    private fun custom(node: XmlNode): Geometry? {
        val guides = (node.child("avLst")?.children("gd").orEmpty() + node.child("gdLst")?.children("gd").orEmpty()).mapNotNull { gd ->
            val name = gd.attr("name") ?: return@mapNotNull null
            gd.attr("fmla")?.removePrefix("val")?.trim()?.toFloatOrNull()?.let { name to it }
        }.toMap()
        fun v(text: String?): Float = text?.toFloatOrNull() ?: guides[text] ?: 0f
        val paths = node.child("pathLst")?.children("path").orEmpty().map { path ->
            val commands = path.children.mapNotNull { c ->
                val pts = c.children("pt")
                when (c.name) {
                    "moveTo" -> pts.firstOrNull()?.let { PathCommand.MoveTo(v(it.attr("x")), v(it.attr("y"))) }
                    "lnTo" -> pts.firstOrNull()?.let { PathCommand.LineTo(v(it.attr("x")), v(it.attr("y"))) }
                    "cubicBezTo" -> if (pts.size >= 3) {
                        PathCommand.CubicTo(v(pts[0].attr("x")), v(pts[0].attr("y")), v(pts[1].attr("x")), v(pts[1].attr("y")), v(pts[2].attr("x")), v(pts[2].attr("y")))
                    } else {
                        null
                    }
                    "quadBezTo" -> if (pts.size >= 2) PathCommand.QuadTo(v(pts[0].attr("x")), v(pts[0].attr("y")), v(pts[1].attr("x")), v(pts[1].attr("y"))) else null
                    "arcTo" -> PathCommand.ArcTo(v(c.attr("wR")), v(c.attr("hR")), v(c.attr("stAng")) / 60_000f, v(c.attr("swAng")) / 60_000f)
                    "close" -> PathCommand.Close
                    else -> null
                }
            }
            CustomPath(
                width = path.attr("w")?.toFloatOrNull() ?: 0f,
                height = path.attr("h")?.toFloatOrNull() ?: 0f,
                fill = path.attr("fill") != "none",
                stroke = !path.attr("stroke").isFalse(),
                commands = commands,
            )
        }
        return if (paths.isEmpty()) null else Geometry.Custom(paths)
    }

    /** The fill set directly inside [container] (`spPr`, `bgPr`, `tcPr`...), or null when it doesn't say. */
    private fun fill(container: XmlNode?, ctx: Ctx, placeholder: Int? = null): Fill? =
        container?.children?.firstNotNullOfOrNull { fillElement(it, ctx, placeholder) }

    private fun fillElement(node: XmlNode, ctx: Ctx, placeholder: Int?): Fill? = when (node.name) {
        "noFill" -> Fill.None
        "solidFill" -> ctx.colors.color(node, placeholder)?.let { Fill.Solid(it) }
        "gradFill" -> {
            val stops = node.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs ->
                val color = ctx.colors.color(gs, placeholder) ?: return@mapNotNull null
                ((gs.attr("pos")?.toFloatOrNull() ?: 0f) / 100_000f).coerceIn(0f, 1f) to color
            }.sortedBy { it.first }
            when {
                stops.isEmpty() -> null
                stops.size == 1 -> Fill.Solid(stops[0].second)
                else -> Fill.Gradient(stops, (node.child("lin")?.attr("ang")?.toFloatOrNull() ?: 0f) / 60_000f, node.child("path") != null)
            }
        }
        "blipFill" -> node.child("blip")?.relAttr("embed")?.let(ctx.rels::get)?.takeIf { !it.external && pkg.has(it.target) }
            ?.let { Fill.Picture(it.target) } ?: Fill.None
        "pattFill" -> {
            // Hatching drawn as the blend of its two colours, which reads the same from a distance.
            val fg = ctx.colors.color(node.child("fgClr"), placeholder) ?: BLACK
            val bg = ctx.colors.color(node.child("bgClr"), placeholder) ?: WHITE
            Fill.Solid(blend(fg, bg, 0.3f))
        }
        "grpFill" -> Fill.None
        else -> null
    }

    /** A theme fill picked by a shape's style (`fillRef`), coloured with the style's colour. */
    private fun styleFill(style: XmlNode?, ctx: Ctx): Fill? {
        val ref = style?.child("fillRef") ?: return null
        val idx = ref.attr("idx")?.toIntOrNull() ?: return null
        if (idx == 0) return Fill.None
        val color = ctx.colors.color(ref)
        val node = when {
            idx >= 1001 -> ctx.theme.backgroundFills.getOrNull(idx - 1001)
            else -> ctx.theme.fillStyles.getOrNull(idx - 1)
        }
        return node?.let { fillElement(it, ctx, color) } ?: color?.let { Fill.Solid(it) }
    }

    private fun background(bg: XmlNode?, ctx: Ctx): Fill? {
        bg ?: return null
        bg.child("bgPr")?.let { return fill(it, ctx) }
        val ref = bg.child("bgRef") ?: return null
        val idx = ref.attr("idx")?.toIntOrNull() ?: return null
        val color = ctx.colors.color(ref)
        val node = if (idx >= 1001) ctx.theme.backgroundFills.getOrNull(idx - 1001) else ctx.theme.fillStyles.getOrNull(idx - 1)
        return node?.let { fillElement(it, ctx, color) } ?: color?.let { Fill.Solid(it) }
    }

    /**
     * A shape's outline from its own `ln` (and inherited ones, nearest first), filling gaps from the
     * theme line its style refers to.
     */
    private fun stroke(lines: List<XmlNode?>, style: XmlNode?, ctx: Ctx): Stroke? {
        val ref = style?.child("lnRef")
        val refIdx = ref?.attr("idx")?.toIntOrNull() ?: 0
        val refColor = ref?.let { ctx.colors.color(it) }
        val themeLine = if (refIdx > 0) ctx.theme.lineStyles.getOrNull(refIdx - 1) else null
        val sources = lines.filterNotNull()
        if (sources.isEmpty() && themeLine == null) return null

        var color: Int? = null
        var decided = false
        for (ln in sources) {
            val fillNode = ln.children.firstOrNull { it.name in FILL_NAMES || it.name == "pattFill" } ?: continue
            decided = true
            color = when (fillNode.name) {
                "noFill" -> null
                "solidFill" -> ctx.colors.color(fillNode, refColor)
                "gradFill" -> fillNode.child("gsLst")?.children("gs")?.firstOrNull()?.let { ctx.colors.color(it, refColor) }
                else -> ctx.colors.color(fillNode.child("fgClr"), refColor)
            }
            break
        }
        if (!decided) {
            color = when {
                themeLine != null -> themeLine.children.firstOrNull { it.name in FILL_NAMES }?.let { if (it.name == "noFill") null else ctx.colors.color(it, refColor) }
                    ?: refColor
                else -> null
            }
        }
        color ?: return null
        val all = sources + listOfNotNull(themeLine)
        val width = all.firstNotNullOfOrNull { it.attr("w")?.toFloatOrNull() } ?: 9_525f
        val dash = all.firstNotNullOfOrNull { it.child("prstDash")?.attr("val") }?.let(::dashPattern)
        fun arrow(name: String): Arrow? {
            val end = sources.firstNotNullOfOrNull { it.child(name) } ?: return null
            val type = when (end.attr("type")) {
                "triangle" -> ArrowType.TRIANGLE
                "stealth" -> ArrowType.STEALTH
                "arrow" -> ArrowType.ARROW
                "oval" -> ArrowType.OVAL
                "diamond" -> ArrowType.DIAMOND
                else -> return null
            }
            fun scale(v: String?) = when (v) {
                "sm" -> 2f
                "lg" -> 5f
                else -> 3f
            }
            return Arrow(type, scale(end.attr("w")), scale(end.attr("len")))
        }
        return Stroke(width, color, dash, arrow("headEnd"), arrow("tailEnd"))
    }

    private fun dashPattern(name: String): FloatArray? = when (name) {
        "dash", "sysDash" -> floatArrayOf(4f, 3f)
        "dot", "sysDot" -> floatArrayOf(1f, 2f)
        "lgDash" -> floatArrayOf(8f, 3f)
        "dashDot", "sysDashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
        "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
        "lgDashDotDot", "sysDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
        else -> null
    }

    companion object {
        const val DEFAULT_WIDTH = 9_144_000f
        const val DEFAULT_HEIGHT = 6_858_000f
        private const val MAX_SLIDES = 2_000
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val NO_STYLE_ID = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"
        private const val NO_STYLE_GRID_ID = "{5940675A-B579-460E-94D1-54222C63F5DA}"
        private val BULLET_KINDS = listOf("buNone", "buChar", "buAutoNum", "buBlip")
        private val FILL_NAMES = setOf("noFill", "solidFill", "gradFill")
        private val LATIN = setOf("latin")

        /** [amount] of [color] over [base]. */
        fun blend(color: Int, base: Int, amount: Float): Int {
            fun ch(shift: Int) = (((color shr shift) and 0xFF) * amount + ((base shr shift) and 0xFF) * (1 - amount)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** A made-up element, to reuse readers that expect one wrapped around another. */
private fun wrap(name: String, child: XmlNode) = XmlNode(name, emptyMap()).also { it.add(child) }

private fun String?.isTrue() = this == "1" || this.equals("true", ignoreCase = true) || this == "on"

private fun String?.isFalse() = this == "0" || this.equals("false", ignoreCase = true) || this == "off"
