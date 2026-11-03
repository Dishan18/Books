package io.github.dishan18.books.office.slides

/**
 * A presentation ready to draw: every inherited style (theme, master, layout, placeholder) already
 * resolved. Positions and sizes are in EMUs (914,400 per inch), as in the file; font sizes are in
 * points; colours are ARGB.
 */
class Presentation(val width: Float, val height: Float, val slides: List<Slide>)

class Slide(val number: Int, val background: Fill?, val shapes: List<Shape>)

/** A box, rotated [rotation] degrees clockwise about its centre, and optionally mirrored. */
data class Frame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

sealed interface Fill {
    data object None : Fill
    data class Solid(val color: Int) : Fill

    /** [stops] are positions 0..1 with colours; [angle] in degrees for a linear gradient. */
    data class Gradient(val stops: List<Pair<Float, Int>>, val angle: Float, val radial: Boolean) : Fill
    data class Picture(val part: String) : Fill
}

enum class ArrowType { NONE, TRIANGLE, STEALTH, ARROW, OVAL, DIAMOND }

data class Arrow(val type: ArrowType, val widthScale: Float, val lengthScale: Float)

/** An outline. [dash] lists dash and gap lengths as multiples of the line width; null is solid. */
data class Stroke(val width: Float, val color: Int, val dash: FloatArray? = null, val head: Arrow? = null, val tail: Arrow? = null) {
    override fun equals(other: Any?) = other is Stroke && width == other.width && color == other.color &&
        dash.contentEquals(other.dash) && head == other.head && tail == other.tail

    override fun hashCode() = (width.hashCode() * 31 + color) * 31 + dash.contentHashCode()
}

sealed interface Geometry {
    /** One of PowerPoint's named shapes ("rect", "ellipse", "rightArrow"), with its adjustment values. */
    data class Preset(val name: String, val adjust: Map<String, Float> = emptyMap()) : Geometry

    /** A freeform shape drawn from its own paths. */
    data class Custom(val paths: List<CustomPath>) : Geometry
}

/** A freeform path in its own [width]×[height] coordinate space, stretched to the shape's box. */
class CustomPath(val width: Float, val height: Float, val fill: Boolean, val stroke: Boolean, val commands: List<PathCommand>)

sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCommand
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCommand

    /** An elliptical arc from the current point; angles in degrees. */
    data class ArcTo(val radiusX: Float, val radiusY: Float, val startAngle: Float, val sweepAngle: Float) : PathCommand
    data object Close : PathCommand
}

enum class TextAnchor { TOP, MIDDLE, BOTTOM }

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

/** Line or paragraph spacing: in points, or as a share of the line height (1 = single). */
sealed interface Spacing {
    data class Points(val points: Float) : Spacing
    data class Percent(val fraction: Float) : Spacing
}

data class Bullet(val text: String, val color: Int?, val sizeRatio: Float, val font: String?)

data class TextRun(
    val text: String,
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: Int,
    val font: String,
    /** Raised (positive) or lowered (negative) as a share of the font size. */
    val baseline: Float = 0f,
    val highlight: Int? = null,
)

data class Paragraph(
    val align: Align,
    val marginLeft: Float,
    val indent: Float,
    val spaceBefore: Spacing,
    val spaceAfter: Spacing,
    val lineSpacing: Spacing,
    val bullet: Bullet?,
    val runs: List<TextRun>,
    /** Size of an empty paragraph's line (its end mark), in points. */
    val endSize: Float,
) {
    val isEmpty get() = runs.all { it.text.isEmpty() }
}

data class TextBody(
    val insetLeft: Float,
    val insetTop: Float,
    val insetRight: Float,
    val insetBottom: Float,
    val anchor: TextAnchor,
    val anchorCenter: Boolean,
    val wrap: Boolean,
    /** 0, 90 or 270 degrees for vertical text. */
    val vertical: Int,
    /** PowerPoint's "shrink text on overflow": font and line spacing scale. */
    val fontScale: Float,
    val lineReduction: Float,
    val paragraphs: List<Paragraph>,
    /** Where the text goes when it isn't the shape's own box (SmartArt). */
    val frame: Frame? = null,
)

data class TableCell(
    val text: TextBody?,
    val fill: Fill?,
    val gridSpan: Int,
    val rowSpan: Int,
    /** Covered by a cell to its left or above that spans over it. */
    val merged: Boolean,
    val left: Stroke?,
    val right: Stroke?,
    val top: Stroke?,
    val bottom: Stroke?,
)

class TableRow(val height: Float, val cells: List<TableCell>)

sealed interface Shape {
    val frame: Frame

    data class Box(
        override val frame: Frame,
        val geometry: Geometry,
        val fill: Fill?,
        val stroke: Stroke?,
        val text: TextBody?,
    ) : Shape

    data class Picture(
        override val frame: Frame,
        val part: String?,
        val geometry: Geometry,
        /** Share of the image cut from each side (left, top, right, bottom); negative adds space. */
        val crop: FloatArray,
        val stroke: Stroke?,
    ) : Shape {
        override fun equals(other: Any?) = other is Picture && frame == other.frame && part == other.part && crop.contentEquals(other.crop)
        override fun hashCode() = frame.hashCode() * 31 + (part?.hashCode() ?: 0)
    }

    /** Shapes positioned in their own coordinate space ([childX], [childY], [childWidth]×[childHeight]) mapped onto [frame]. */
    data class Group(
        override val frame: Frame,
        val childX: Float,
        val childY: Float,
        val childWidth: Float,
        val childHeight: Float,
        val children: List<Shape>,
    ) : Shape

    data class Table(override val frame: Frame, val columns: List<Float>, val rows: List<TableRow>) : Shape

    /** Something Books doesn't draw (a chart, an embedded object): a labelled box keeps the layout readable. */
    data class Unsupported(override val frame: Frame, val label: String) : Shape
}
