package io.github.dishan18.books.office.slides

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pictures inside the presentation, decoded at about the size they're drawn. */
fun interface ImageSource {
    fun bitmap(part: String, width: Int, height: Int): Bitmap?
}

/**
 * Draws slides onto a canvas whose origin is the slide's top-left corner, at [Canvas] pixels per
 * EMU given by the caller. Not thread-safe; use from one thread at a time.
 */
class SlideRenderer(private val presentation: Presentation, private val images: ImageSource) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val typefaces = HashMap<String, Typeface>()

    /** Pixels per EMU for the slide as a whole; fonts, lines and margins scale with it. */
    private var scale = 1f

    fun draw(canvas: Canvas, index: Int, pixelsPerEmu: Float) {
        val slide = presentation.slides.getOrNull(index) ?: return
        scale = pixelsPerEmu
        val bounds = RectF(0f, 0f, presentation.width * scale, presentation.height * scale)
        canvas.save()
        canvas.clipRect(bounds)
        val background = slide.background ?: Fill.Solid(WHITE)
        val path = Path().apply { addRect(bounds, Path.Direction.CW) }
        if (!paintFill(canvas, background, path, bounds)) canvas.drawRect(bounds, fill.apply { shader = null; color = WHITE })
        val root = Mapping(scale, scale, 0f, 0f)
        slide.shapes.forEach { shape -> runCatching { drawShape(canvas, shape, root) } }
        canvas.restore()
    }

    /** Maps a coordinate space in EMUs onto canvas pixels (groups have their own). */
    private class Mapping(val sx: Float, val sy: Float, val ox: Float, val oy: Float) {
        fun rect(f: Frame) = RectF(ox + f.x * sx, oy + f.y * sy, ox + (f.x + f.width) * sx, oy + (f.y + f.height) * sy)

        fun child(group: Shape.Group): Mapping {
            val kx = if (group.childWidth > 0) group.frame.width / group.childWidth else 1f
            val ky = if (group.childHeight > 0) group.frame.height / group.childHeight else 1f
            return Mapping(sx * kx, sy * ky, ox + (group.frame.x - group.childX * kx) * sx, oy + (group.frame.y - group.childY * ky) * sy)
        }
    }

    private fun drawShape(canvas: Canvas, shape: Shape, m: Mapping) {
        when (shape) {
            is Shape.Box -> drawBox(canvas, shape, m)
            is Shape.Picture -> drawPicture(canvas, shape, m)
            is Shape.Group -> {
                val r = m.rect(shape.frame)
                canvas.save()
                transform(canvas, shape.frame, r)
                val child = m.child(shape)
                shape.children.forEach { runCatching { drawShape(canvas, it, child) } }
                canvas.restore()
            }
            is Shape.Table -> drawTable(canvas, shape, m)
            is Shape.Unsupported -> drawUnsupported(canvas, shape, m)
        }
    }

    /** Rotation about the centre, after any mirroring. */
    private fun transform(canvas: Canvas, frame: Frame, r: RectF) {
        if (frame.rotation != 0f) canvas.rotate(frame.rotation, r.centerX(), r.centerY())
        if (frame.flipH || frame.flipV) canvas.scale(if (frame.flipH) -1f else 1f, if (frame.flipV) -1f else 1f, r.centerX(), r.centerY())
    }

    private fun drawBox(canvas: Canvas, box: Shape.Box, m: Mapping) {
        val r = m.rect(box.frame)
        canvas.save()
        transform(canvas, box.frame, r)
        val paths = geometryPaths(box.geometry, r)
        box.fill?.let { f -> paths.filter { it.fill }.forEach { paintFill(canvas, f, it.path, r) } }
        box.stroke?.let { s ->
            paths.filter { it.stroke }.forEach { stroke(canvas, s, it.path) }
            arrowheads(canvas, box.geometry, r, s)
        }
        canvas.restore()

        val text = box.text ?: return
        if (text.paragraphs.all { it.isEmpty }) return
        val textRect = text.frame?.let(m::rect) ?: r
        canvas.save()
        // Text turns with its shape but is never drawn mirrored; a vertical flip turns it upside down.
        val turn = box.frame.rotation + if (box.frame.flipV) 180f else 0f
        if (turn != 0f) canvas.rotate(turn, r.centerX(), r.centerY())
        drawText(canvas, text, textRect)
        canvas.restore()
    }

    private fun drawPicture(canvas: Canvas, picture: Shape.Picture, m: Mapping) {
        val r = m.rect(picture.frame)
        if (r.width() < 0.5f || r.height() < 0.5f) return
        canvas.save()
        transform(canvas, picture.frame, r)
        val clip = geometryPaths(picture.geometry, r).firstOrNull { it.fill }?.path ?: Path().apply { addRect(r, Path.Direction.CW) }
        val (l, t, rr, b) = picture.crop.toList().let { it + List(4 - it.size) { 0f } }
        val visibleW = 1f - l - rr
        val visibleH = 1f - t - b
        val part = picture.part
        if (part != null && visibleW > 0.001f && visibleH > 0.001f) {
            val fullW = r.width() / visibleW
            val fullH = r.height() / visibleH
            val bitmap = images.bitmap(part, fullW.roundToInt().coerceAtLeast(1), fullH.roundToInt().coerceAtLeast(1))
            if (bitmap != null) {
                canvas.save()
                canvas.clipPath(clip)
                val dst = RectF(r.left - l * fullW, r.top - t * fullH, r.left - l * fullW + fullW, r.top - t * fullH + fullH)
                canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
                canvas.restore()
            } else {
                missingPicture(canvas, clip)
            }
        } else if (part == null) {
            missingPicture(canvas, clip)
        }
        picture.stroke?.let { stroke(canvas, it, clip) }
        canvas.restore()
    }

    /** A picture Books can't decode (a Windows metafile, say): a quiet tile where it would be. */
    private fun missingPicture(canvas: Canvas, clip: Path) {
        fill.shader = null
        fill.color = 0xFFEDEDED.toInt()
        canvas.drawPath(clip, fill)
    }

    private fun drawUnsupported(canvas: Canvas, shape: Shape.Unsupported, m: Mapping) {
        val r = m.rect(shape.frame)
        if (r.width() < 2f || r.height() < 2f) return
        val radius = min(r.width(), r.height()) * 0.06f
        fill.shader = null
        fill.color = 0xFFF2F2F2.toInt()
        canvas.drawRoundRect(r, radius, radius, fill)
        line.pathEffect = null
        line.shader = null
        line.color = 0xFFC9C9C9.toInt()
        line.strokeWidth = max(1f, 9_525f * scale)
        canvas.drawRoundRect(r, radius, radius, line)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7A7A7A.toInt()
            textSize = (min(r.width(), r.height()) * 0.12f).coerceIn(4f, max(4f, 36 * PT * scale))
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(shape.label, r.centerX(), r.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
    }

    // Fills and lines

    /** Paints [f] inside [path]; false when there was nothing to paint. */
    private fun paintFill(canvas: Canvas, f: Fill, path: Path, r: RectF): Boolean {
        when (f) {
            Fill.None -> return false
            is Fill.Solid -> {
                fill.shader = null
                fill.color = f.color
                canvas.drawPath(path, fill)
            }
            is Fill.Gradient -> {
                val colors = f.stops.map { it.second }.toIntArray()
                val positions = f.stops.map { it.first }.toFloatArray()
                fill.shader = if (f.radial) {
                    RadialGradient(r.centerX(), r.centerY(), max(1f, hypot(r.width(), r.height()) / 2f), colors, positions, Shader.TileMode.CLAMP)
                } else {
                    val angle = Math.toRadians(f.angle.toDouble())
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
                    LinearGradient(
                        r.centerX() - dx * half, r.centerY() - dy * half, r.centerX() + dx * half, r.centerY() + dy * half,
                        colors, positions, Shader.TileMode.CLAMP,
                    )
                }
                fill.color = BLACK
                canvas.drawPath(path, fill)
                fill.shader = null
            }
            is Fill.Picture -> {
                val bitmap = images.bitmap(f.part, r.width().roundToInt().coerceAtLeast(1), r.height().roundToInt().coerceAtLeast(1)) ?: return false
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bitmap, null, r, bitmapPaint)
                canvas.restore()
            }
        }
        return true
    }

    private fun strokeWidth(s: Stroke) = max(1f, s.width * scale)

    private fun stroke(canvas: Canvas, s: Stroke, path: Path) {
        val width = strokeWidth(s)
        line.shader = null
        line.color = s.color
        line.strokeWidth = width
        line.pathEffect = s.dash?.let { d -> DashPathEffect(FloatArray(d.size) { d[it] * width }, 0f) }
        canvas.drawPath(path, line)
        line.pathEffect = null
    }

    private fun arrowheads(canvas: Canvas, geometry: Geometry, r: RectF, s: Stroke) {
        if (s.head == null && s.tail == null) return
        val name = (geometry as? Geometry.Preset)?.name ?: return
        val (start, startFrom, end, endFrom) = when {
            name == "line" || name == "straightConnector1" -> listOf(r.left to r.top, r.right to r.bottom, r.right to r.bottom, r.left to r.top)
            name == "bentConnector2" -> listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.right to r.top)
            name.startsWith("bentConnector") || name.startsWith("curvedConnector") -> {
                val x1 = r.left + r.width() * ((geometry.adjust["adj1"] ?: 50_000f) / 100_000f)
                listOf(r.left to r.top, x1 to r.top, r.right to r.bottom, x1 to r.bottom)
            }
            else -> return
        }
        s.head?.let { arrow(canvas, it, start, startFrom, s) }
        s.tail?.let { arrow(canvas, it, end, endFrom, s) }
    }

    /** An arrowhead with its tip at [tip], pointing away from [from]. */
    private fun arrow(canvas: Canvas, a: Arrow, tip: Pair<Float, Float>, from: Pair<Float, Float>, s: Stroke) {
        val width = strokeWidth(s)
        var dx = tip.first - from.first
        var dy = tip.second - from.second
        val length = hypot(dx, dy)
        if (length < 0.01f) return
        dx /= length
        dy /= length
        val headLength = width * a.lengthScale
        val headWidth = width * a.widthScale / 2f
        val bx = tip.first - dx * headLength
        val by = tip.second - dy * headLength
        val nx = -dy
        val ny = dx
        fill.shader = null
        fill.color = s.color
        when (a.type) {
            ArrowType.OVAL -> canvas.drawOval(RectF(tip.first - headWidth, tip.second - headWidth, tip.first + headWidth, tip.second + headWidth), fill)
            ArrowType.DIAMOND -> canvas.drawPath(
                Path().apply {
                    // Centred on the line's end.
                    moveTo(tip.first + dx * headLength / 2, tip.second + dy * headLength / 2)
                    lineTo(tip.first + nx * headWidth, tip.second + ny * headWidth)
                    lineTo(tip.first - dx * headLength / 2, tip.second - dy * headLength / 2)
                    lineTo(tip.first - nx * headWidth, tip.second - ny * headWidth)
                    close()
                },
                fill,
            )
            ArrowType.ARROW -> {
                line.color = s.color
                line.strokeWidth = width
                line.pathEffect = null
                canvas.drawPath(
                    Path().apply {
                        moveTo(bx + nx * headWidth, by + ny * headWidth)
                        lineTo(tip.first, tip.second)
                        lineTo(bx - nx * headWidth, by - ny * headWidth)
                    },
                    line,
                )
            }
            else -> {
                val notch = if (a.type == ArrowType.STEALTH) headLength * 0.3f else 0f
                canvas.drawPath(
                    Path().apply {
                        moveTo(tip.first, tip.second)
                        lineTo(bx + nx * headWidth, by + ny * headWidth)
                        lineTo(bx + dx * notch, by + dy * notch)
                        lineTo(bx - nx * headWidth, by - ny * headWidth)
                        close()
                    },
                    fill,
                )
            }
        }
    }

    // Geometry

    private class ShapePath(val path: Path, val fill: Boolean = true, val stroke: Boolean = true)

    private fun geometryPaths(geometry: Geometry, r: RectF): List<ShapePath> = when (geometry) {
        is Geometry.Custom -> geometry.paths.map { customPath(it, r) }
        is Geometry.Preset -> PresetShapes.path(geometry.name, geometry.adjust, r)
    }

    private fun customPath(p: CustomPath, r: RectF): ShapePath {
        val w = if (p.width > 0) p.width else max(1f, r.width() / scale)
        val h = if (p.height > 0) p.height else max(1f, r.height() / scale)
        val kx = r.width() / w
        val ky = r.height() / h
        val path = Path()
        var x = 0f
        var y = 0f
        p.commands.forEach { c ->
            when (c) {
                is PathCommand.MoveTo -> {
                    x = c.x
                    y = c.y
                    path.moveTo(r.left + x * kx, r.top + y * ky)
                }
                is PathCommand.LineTo -> {
                    x = c.x
                    y = c.y
                    path.lineTo(r.left + x * kx, r.top + y * ky)
                }
                is PathCommand.CubicTo -> {
                    path.cubicTo(r.left + c.x1 * kx, r.top + c.y1 * ky, r.left + c.x2 * kx, r.top + c.y2 * ky, r.left + c.x * kx, r.top + c.y * ky)
                    x = c.x
                    y = c.y
                }
                is PathCommand.QuadTo -> {
                    path.quadTo(r.left + c.x1 * kx, r.top + c.y1 * ky, r.left + c.x * kx, r.top + c.y * ky)
                    x = c.x
                    y = c.y
                }
                is PathCommand.ArcTo -> {
                    // DrawingML arcs start at the current point, on an ellipse of the given radii.
                    val start = Math.toRadians(c.startAngle.toDouble())
                    val cx = x - c.radiusX * cos(start).toFloat()
                    val cy = y - c.radiusY * sin(start).toFloat()
                    val oval = RectF(r.left + (cx - c.radiusX) * kx, r.top + (cy - c.radiusY) * ky, r.left + (cx + c.radiusX) * kx, r.top + (cy + c.radiusY) * ky)
                    path.arcTo(oval, c.startAngle, c.sweepAngle)
                    val end = Math.toRadians((c.startAngle + c.sweepAngle).toDouble())
                    x = cx + c.radiusX * cos(end).toFloat()
                    y = cy + c.radiusY * sin(end).toFloat()
                }
                PathCommand.Close -> path.close()
            }
        }
        return ShapePath(path, p.fill, p.stroke)
    }

    // Text

    private fun drawText(canvas: Canvas, body: TextBody, shapeRect: RectF) {
        canvas.save()
        var rect = shapeRect
        if (body.vertical != 0) {
            canvas.rotate(body.vertical.toFloat(), shapeRect.centerX(), shapeRect.centerY())
            val cx = shapeRect.centerX()
            val cy = shapeRect.centerY()
            rect = RectF(cx - shapeRect.height() / 2, cy - shapeRect.width() / 2, cx + shapeRect.height() / 2, cy + shapeRect.width() / 2)
        }
        val box = RectF(
            rect.left + body.insetLeft * scale,
            rect.top + body.insetTop * scale,
            rect.right - body.insetRight * scale,
            rect.bottom - body.insetBottom * scale,
        )
        val width = max(1f, box.width())
        val laid = body.paragraphs.map { layoutParagraph(it, body, width) }
        var total = 0f
        laid.forEachIndexed { i, p -> total += (if (i > 0) p.spaceBefore else 0f) + p.layout.height + p.spaceAfter }
        var y = box.top + when (body.anchor) {
            TextAnchor.TOP -> 0f
            TextAnchor.MIDDLE -> (box.height() - total) / 2f
            TextAnchor.BOTTOM -> box.height() - total
        }
        laid.forEachIndexed { i, p ->
            if (i > 0) y += p.spaceBefore
            canvas.save()
            canvas.translate(box.left + p.offsetX, y)
            p.layout.draw(canvas)
            p.bullet?.let { b -> canvas.drawText(b.text, b.x, p.layout.getLineBaseline(0).toFloat(), b.paint) }
            canvas.restore()
            y += p.layout.height + p.spaceAfter
        }
        canvas.restore()
    }

    private class LaidBullet(val text: String, val x: Float, val paint: TextPaint)

    private class LaidParagraph(val layout: StaticLayout, val offsetX: Float, val spaceBefore: Float, val spaceAfter: Float, val bullet: LaidBullet?)

    private fun px(points: Float, body: TextBody) = points * PT * scale * body.fontScale

    private fun layoutParagraph(p: Paragraph, body: TextBody, width: Float): LaidParagraph {
        val text = SpannableStringBuilder()
        p.runs.forEach { run ->
            if (run.text.isEmpty()) return@forEach
            val start = text.length
            text.append(run.text.replace('\u000B', '\n'))
            val size = px(run.size, body) * if (run.baseline != 0f) SCRIPT_SCALE else 1f
            text.setSpan(
                RunSpan(size, typeface(run.font, run.bold, run.italic), run.color, run.underline, run.strike, (-run.baseline * px(run.size, body)).roundToInt()),
                start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            run.highlight?.let { text.setSpan(BackgroundColorSpan(it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val firstRun = p.runs.firstOrNull { it.text.isNotBlank() }
        val baseSize = px(firstRun?.size ?: p.endSize, body)
        if (text.isEmpty()) {
            // An empty line still takes up a line's height at its own size.
            text.append('​')
            text.setSpan(RunSpan(px(p.endSize, body), typeface(firstRun?.font ?: "", false, false), BLACK, false, false, 0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = baseSize.coerceAtLeast(1f)
            color = firstRun?.color ?: BLACK
        }

        val marginLeft = p.marginLeft * scale
        val indent = p.indent * scale
        var laidBullet: LaidBullet? = null
        var firstStart = (marginLeft + indent).coerceAtLeast(0f)
        p.bullet?.let { b ->
            val bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = (baseSize * b.sizeRatio).coerceAtLeast(1f)
                color = b.color ?: firstRun?.color ?: BLACK
                typeface = b.font?.let { typeface(it, false, false) } ?: typeface(firstRun?.font ?: "", firstRun?.bold == true, false)
            }
            val bulletText = bulletText(b)
            val bulletWidth = bulletPaint.measureText(bulletText)
            val bulletX = (marginLeft + indent).coerceAtLeast(0f)
            // Text starts at the left margin, or just after the bullet when the bullet reaches past it.
            firstStart = max(marginLeft, bulletX + bulletWidth + bulletPaint.textSize * 0.25f)
            laidBullet = LaidBullet(bulletText, bulletX, bulletPaint)
        }
        val restStart = marginLeft.coerceAtLeast(0f)
        if (firstStart > 0f || restStart > 0f) {
            text.setSpan(LeadingMarginSpan.Standard(firstStart.roundToInt(), restStart.roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

        var multiplier = 1f
        when (val ls = p.lineSpacing) {
            is Spacing.Percent -> multiplier = (ls.fraction * (1f - body.lineReduction)).coerceIn(0.1f, 10f)
            is Spacing.Points -> {
                val height = (ls.points * PT * scale * (1f - body.lineReduction)).roundToInt().coerceAtLeast(1)
                text.setSpan(LineHeightSpan.Standard(height), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }

        var layoutWidth = width
        var offsetX = 0f
        if (!body.wrap) {
            val desired = Layout.getDesiredWidth(text, paint) + max(firstStart, restStart)
            if (desired > width) {
                layoutWidth = desired
                offsetX = when (p.align) {
                    Align.CENTER -> (width - desired) / 2f
                    Align.RIGHT -> width - desired
                    else -> 0f
                }
            }
        }
        val alignment = when (p.align) {
            Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, layoutWidth.roundToInt()))
            .setAlignment(alignment)
            .setLineSpacing(0f, multiplier)
            .setIncludePad(false)
            .setJustificationMode(if (p.align == Align.JUSTIFY) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .build()

        // A centred or right-aligned list puts each bullet just before its line.
        laidBullet = laidBullet?.let { b ->
            if (p.align == Align.CENTER || p.align == Align.RIGHT) {
                LaidBullet(b.text, layout.getLineLeft(0) - b.paint.measureText(b.text) - b.paint.textSize * 0.25f, b.paint)
            } else {
                b
            }
        }

        fun spacing(s: Spacing): Float = when (s) {
            is Spacing.Points -> s.points * PT * scale * (1f - body.lineReduction)
            is Spacing.Percent -> s.fraction * baseSize * 1.2f * (1f - body.lineReduction)
        }
        return LaidParagraph(layout, offsetX, spacing(p.spaceBefore), spacing(p.spaceAfter), laidBullet)
    }

    /** Symbol-font bullets (Wingdings, Symbol) mapped to the same picture in ordinary fonts. */
    private fun bulletText(b: Bullet): String {
        val font = b.font?.lowercase().orEmpty()
        val raw = b.text
        if (raw.length != 1) return raw
        var c = raw[0]
        if (c.code in 0xF000..0xF0FF) c = (c.code - 0xF000).toChar()
        return when {
            font.startsWith("wingdings") -> WINGDINGS[c] ?: "•"
            font == "symbol" -> if (c == '·' || c.code == 0xB7) "•" else c.toString()
            else -> c.toString()
        }
    }

    private fun typeface(name: String, bold: Boolean, italic: Boolean): Typeface {
        val key = "$name|$bold|$italic"
        return typefaces.getOrPut(key) {
            val lower = name.lowercase()
            val family = when {
                MONO.any { it in lower } -> "monospace"
                SERIF.any { it in lower } && "sans" !in lower -> "serif"
                "comic" in lower -> "casual"
                SCRIPT.any { it in lower } -> "cursive"
                "narrow" in lower || "condensed" in lower -> "sans-serif-condensed"
                "light" in lower || "thin" in lower -> "sans-serif-light"
                "black" in lower || "heavy" in lower -> "sans-serif-black"
                "medium" in lower || "semibold" in lower -> "sans-serif-medium"
                else -> "sans-serif"
            }
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(family, style)
        }
    }

    /** One run's look; the size is a float so text scales smoothly at any zoom. */
    private class RunSpan(
        private val size: Float,
        private val typeface: Typeface,
        private val color: Int,
        private val underline: Boolean,
        private val strike: Boolean,
        private val shift: Int,
    ) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            updateMeasureState(tp)
            tp.color = color
            tp.isUnderlineText = underline
            tp.isStrikeThruText = strike
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.textSize = size.coerceAtLeast(0.5f)
            tp.typeface = typeface
            tp.baselineShift += shift
        }
    }

    // Tables

    private fun drawTable(canvas: Canvas, table: Shape.Table, m: Mapping) {
        val r = m.rect(table.frame)
        val xs = FloatArray(table.columns.size + 1).also { a ->
            a[0] = r.left
            table.columns.forEachIndexed { i, w -> a[i + 1] = a[i] + w * m.sx }
        }
        val ys = FloatArray(table.rows.size + 1).also { a ->
            a[0] = r.top
            table.rows.forEachIndexed { i, row -> a[i + 1] = a[i] + row.height * m.sy }
        }
        val lines = mutableListOf<Triple<Stroke, Pair<Float, Float>, Pair<Float, Float>>>()
        table.rows.forEachIndexed { ri, row ->
            row.cells.forEachIndexed { ci, cell ->
                if (cell.merged || ci >= table.columns.size) return@forEachIndexed
                val right = xs[(ci + cell.gridSpan).coerceAtMost(table.columns.size)]
                val bottom = ys[(ri + cell.rowSpan).coerceAtMost(table.rows.size)]
                val rect = RectF(xs[ci], ys[ri], right, bottom)
                cell.fill?.let { paintFill(canvas, it, Path().apply { addRect(rect, Path.Direction.CW) }, rect) }
                cell.text?.let { if (!it.paragraphs.all { p -> p.isEmpty }) drawText(canvas, it, rect) }
                cell.left?.let { lines += Triple(it, rect.left to rect.top, rect.left to rect.bottom) }
                cell.right?.let { lines += Triple(it, rect.right to rect.top, rect.right to rect.bottom) }
                cell.top?.let { lines += Triple(it, rect.left to rect.top, rect.right to rect.top) }
                cell.bottom?.let { lines += Triple(it, rect.left to rect.bottom, rect.right to rect.bottom) }
            }
        }
        // Borders last, so neighbouring fills don't cover them.
        lines.forEach { (s, a, b) -> stroke(canvas, s, Path().apply { moveTo(a.first, a.second); lineTo(b.first, b.second) }) }
    }

    /** Named shapes, built from their definitions' proportions and adjustment values. */
    private object PresetShapes {
        fun path(name: String, adjust: Map<String, Float>, r: RectF): List<ShapePath> {
            val l = r.left
            val t = r.top
            val rr = r.right
            val b = r.bottom
            val w = r.width()
            val h = r.height()
            val ss = min(w, h)
            val cx = r.centerX()
            val cy = r.centerY()
            fun a(key: String, default: Float) = (adjust[key] ?: adjust["adj"]?.takeIf { key == "adj1" } ?: default) / 100_000f
            fun poly(vararg pts: Float): Path = Path().apply {
                moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) {
                    lineTo(pts[i], pts[i + 1])
                    i += 2
                }
                close()
            }
            fun open(path: Path) = listOf(ShapePath(path, fill = false, stroke = true))
            fun closed(path: Path) = listOf(ShapePath(path))
            fun roundRect(radius: Float) = closed(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })

            return when (name) {
                "roundRect", "flowChartAlternateProcess", "round1Rect", "round2SameRect", "round2DiagRect", "snipRoundRect",
                "snip1Rect", "snip2SameRect", "snip2DiagRect", "plaque",
                -> roundRect(ss * (adjust["adj"] ?: adjust["adj1"] ?: 16_667f) / 100_000f)
                "flowChartTerminator" -> roundRect(ss / 2f)
                "ellipse", "flowChartConnector", "cloud", "cloudCallout", "teardrop", "flowChartSummingJunction", "flowChartOr" ->
                    closed(Path().apply { addOval(r, Path.Direction.CW) })
                "line", "straightConnector1" -> open(Path().apply { moveTo(l, t); lineTo(rr, b) })
                "bentConnector2" -> open(Path().apply { moveTo(l, t); lineTo(rr, t); lineTo(rr, b) })
                "bentConnector3", "bentConnector4", "bentConnector5" -> {
                    val x1 = l + w * a("adj1", 50_000f)
                    open(Path().apply { moveTo(l, t); lineTo(x1, t); lineTo(x1, b); lineTo(rr, b) })
                }
                "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5" ->
                    open(Path().apply { moveTo(l, t); cubicTo(cx, t, cx, b, rr, b) })
                "triangle", "flowChartExtract" -> {
                    val apex = l + w * a("adj", 50_000f)
                    closed(poly(apex, t, rr, b, l, b))
                }
                "flowChartMerge" -> closed(poly(l, t, rr, t, cx, b))
                "rtTriangle" -> closed(poly(l, t, rr, b, l, b))
                "diamond", "flowChartDecision", "flowChartSort", "flowChartCollate" -> closed(poly(cx, t, rr, cy, cx, b, l, cy))
                "parallelogram", "flowChartInputOutput" -> {
                    val off = if (name == "flowChartInputOutput") w * 0.2f else ss * a("adj", 25_000f)
                    closed(poly(l + off, t, rr, t, rr - off, b, l, b))
                }
                "trapezoid" -> {
                    val off = ss * a("adj", 25_000f)
                    closed(poly(l + off, t, rr - off, t, rr, b, l, b))
                }
                "flowChartManualOperation" -> closed(poly(l, t, rr, t, rr - w * 0.2f, b, l + w * 0.2f, b))
                "flowChartManualInput" -> closed(poly(l, t + h * 0.2f, rr, t, rr, b, l, b))
                "flowChartOffpageConnector" -> closed(poly(l, t, rr, t, rr, t + h * 0.8f, cx, b, l, t + h * 0.8f))
                "flowChartPunchedCard" -> closed(poly(l + w * 0.2f, t, rr, t, rr, b, l, b, l, t + h * 0.2f))
                "pentagon" -> closed(poly(cx, t, rr, t + h * 0.382f, l + w * 0.809f, b, l + w * 0.191f, b, l, t + h * 0.382f))
                "hexagon", "flowChartPreparation" -> {
                    val off = if (name == "hexagon") ss * a("adj", 25_000f) else w * 0.2f
                    closed(poly(l + off, t, rr - off, t, rr, cy, rr - off, b, l + off, b, l, cy))
                }
                "octagon" -> {
                    val off = ss * a("adj", 29_289f)
                    closed(poly(l + off, t, rr - off, t, rr, t + off, rr, b - off, rr - off, b, l + off, b, l, b - off, l, t + off))
                }
                "homePlate" -> {
                    val off = ss * a("adj", 50_000f)
                    closed(poly(l, t, rr - off, t, rr, cy, rr - off, b, l, b))
                }
                "chevron" -> {
                    val off = ss * a("adj", 50_000f)
                    closed(poly(l, t, rr - off, t, rr, cy, rr - off, b, l, b, l + off, cy))
                }
                "rightArrow", "notchedRightArrow", "stripedRightArrow" -> {
                    val half = h * a("adj1", 50_000f) / 2f
                    val head = rr - ss * a("adj2", 50_000f)
                    closed(poly(l, cy - half, head, cy - half, head, t, rr, cy, head, b, head, cy + half, l, cy + half))
                }
                "leftArrow" -> {
                    val half = h * a("adj1", 50_000f) / 2f
                    val head = l + ss * a("adj2", 50_000f)
                    closed(poly(rr, cy - half, head, cy - half, head, t, l, cy, head, b, head, cy + half, rr, cy + half))
                }
                "upArrow" -> {
                    val half = w * a("adj1", 50_000f) / 2f
                    val head = t + ss * a("adj2", 50_000f)
                    closed(poly(cx - half, b, cx - half, head, l, head, cx, t, rr, head, cx + half, head, cx + half, b))
                }
                "downArrow" -> {
                    val half = w * a("adj1", 50_000f) / 2f
                    val head = b - ss * a("adj2", 50_000f)
                    closed(poly(cx - half, t, cx - half, head, l, head, cx, b, rr, head, cx + half, head, cx + half, t))
                }
                "leftRightArrow" -> {
                    val half = h * a("adj1", 50_000f) / 2f
                    val head = ss * a("adj2", 50_000f)
                    closed(poly(l, cy, l + head, t, l + head, cy - half, rr - head, cy - half, rr - head, t, rr, cy, rr - head, b, rr - head, cy + half, l + head, cy + half, l + head, b))
                }
                "plus" -> {
                    val off = ss * a("adj", 25_000f)
                    closed(poly(l + off, t, rr - off, t, rr - off, t + off, rr, t + off, rr, b - off, rr - off, b - off, rr - off, b, l + off, b, l + off, b - off, l, b - off, l, t + off, l + off, t + off))
                }
                "star5" -> {
                    val inner = a("adj", 19_098f) * 2f
                    val pts = FloatArray(20)
                    for (i in 0 until 10) {
                        val radius = if (i % 2 == 0) 1f else inner.coerceIn(0.1f, 1f)
                        val angle = Math.toRadians(-90.0 + i * 36.0)
                        pts[i * 2] = cx + (w / 2f) * radius * cos(angle).toFloat()
                        pts[i * 2 + 1] = cy + (h / 2f) * radius * sin(angle).toFloat()
                    }
                    closed(poly(*pts))
                }
                "heart" -> closed(
                    Path().apply {
                        moveTo(cx, t + h * 0.3f)
                        cubicTo(cx, t, l, t, l, t + h * 0.3f)
                        cubicTo(l, t + h * 0.6f, cx, t + h * 0.8f, cx, b)
                        cubicTo(cx, t + h * 0.8f, rr, t + h * 0.6f, rr, t + h * 0.3f)
                        cubicTo(rr, t, cx, t, cx, t + h * 0.3f)
                        close()
                    },
                )
                "donut" -> {
                    val inset = ss * a("adj", 25_000f)
                    closed(
                        Path().apply {
                            fillType = Path.FillType.EVEN_ODD
                            addOval(r, Path.Direction.CW)
                            addOval(RectF(l + inset, t + inset, rr - inset, b - inset), Path.Direction.CCW)
                        },
                    )
                }
                "frame" -> {
                    val inset = ss * a("adj1", 12_500f)
                    closed(
                        Path().apply {
                            fillType = Path.FillType.EVEN_ODD
                            addRect(r, Path.Direction.CW)
                            addRect(RectF(l + inset, t + inset, rr - inset, b - inset), Path.Direction.CCW)
                        },
                    )
                }
                "can", "flowChartMagneticDisk" -> {
                    val e = (ss * a("adj", 25_000f)).coerceAtMost(h / 2f)
                    val body = Path().apply {
                        addRect(RectF(l, t + e / 2f, rr, b - e / 2f), Path.Direction.CW)
                        addOval(RectF(l, b - e, rr, b), Path.Direction.CW)
                    }
                    val top = Path().apply { addOval(RectF(l, t, rr, t + e), Path.Direction.CW) }
                    body.op(top, Path.Op.UNION)
                    listOf(ShapePath(body), ShapePath(top, fill = false, stroke = true))
                }
                "pie" -> {
                    val start = (adjust["adj1"] ?: 0f) / 60_000f
                    val end = (adjust["adj2"] ?: 16_200_000f) / 60_000f
                    var sweep = end - start
                    if (sweep <= 0) sweep += 360f
                    closed(Path().apply { moveTo(cx, cy); arcTo(r, start, sweep); close() })
                }
                "arc" -> {
                    val start = (adjust["adj1"] ?: 16_200_000f) / 60_000f
                    val end = (adjust["adj2"] ?: 0f) / 60_000f
                    var sweep = end - start
                    if (sweep <= 0) sweep += 360f
                    open(Path().apply { arcTo(r, start, sweep, true) })
                }
                "leftBracket" -> open(Path().apply { moveTo(rr, t); quadTo(l, t, l, t + h * 0.1f); lineTo(l, b - h * 0.1f); quadTo(l, b, rr, b) })
                "rightBracket" -> open(Path().apply { moveTo(l, t); quadTo(rr, t, rr, t + h * 0.1f); lineTo(rr, b - h * 0.1f); quadTo(rr, b, l, b) })
                "leftBrace" -> open(Path().apply { moveTo(rr, t); quadTo(cx, t, cx, t + h * 0.1f); lineTo(cx, cy - h * 0.05f); quadTo(cx, cy, l, cy); quadTo(cx, cy, cx, cy + h * 0.05f); lineTo(cx, b - h * 0.1f); quadTo(cx, b, rr, b) })
                "rightBrace" -> open(Path().apply { moveTo(l, t); quadTo(cx, t, cx, t + h * 0.1f); lineTo(cx, cy - h * 0.05f); quadTo(cx, cy, rr, cy); quadTo(cx, cy, cx, cy + h * 0.05f); lineTo(cx, b - h * 0.1f); quadTo(cx, b, l, b) })
                "wedgeRectCallout", "wedgeRoundRectCallout", "wedgeEllipseCallout" -> {
                    val tipX = cx + w * ((adjust["adj1"] ?: -20_833f) / 100_000f)
                    val tipY = cy + h * ((adjust["adj2"] ?: 62_500f) / 100_000f)
                    val body = Path().apply {
                        when (name) {
                            "wedgeEllipseCallout" -> addOval(r, Path.Direction.CW)
                            "wedgeRoundRectCallout" -> addRoundRect(r, ss * 0.16667f, ss * 0.16667f, Path.Direction.CW)
                            else -> addRect(r, Path.Direction.CW)
                        }
                    }
                    // The tail starts from a narrow base at the centre, so it joins the body on the side it points to.
                    val base = ss * 0.12f
                    val dx = tipX - cx
                    val dy = tipY - cy
                    val tail = if (abs(dx) > abs(dy)) poly(cx, cy - base, tipX, tipY, cx, cy + base) else poly(cx - base, cy, tipX, tipY, cx + base, cy)
                    body.op(tail, Path.Op.UNION)
                    closed(body)
                }
                else -> closed(Path().apply { addRect(r, Path.Direction.CW) })
            }
        }
    }

    companion object {
        /** EMUs per point. */
        const val PT = 12_700f
        private const val SCRIPT_SCALE = 0.67f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        private val MONO = listOf("courier", "consolas", "mono", "lucida console", "menlo", "monaco", "code")
        private val SERIF = listOf(
            "times", "georgia", "cambria", "garamond", "book antiqua", "palatino", "constantia", "baskerville", "bodoni",
            "century", "didot", "rockwell", "bookman", "serif", "minion", "caslon", "perpetua", "goudy", "sylfaen",
        )
        private val SCRIPT = listOf("script", "handwriting", "brush", "vivaldi", "kunstler", "edwardian", "pristina", "segoe print")

        /** Common Wingdings bullet characters and their look-alikes. */
        private val WINGDINGS = mapOf(
            'l' to "●", 'n' to "■", 'q' to "❑", 'u' to "◆", 'v' to "❖", 'w' to "⬥", 'Ø' to "➢", 'ü' to "✓", 'û' to "✗",
            '§' to "▪", 'Ÿ' to "•", 'à' to "→", 'è' to "➔", 'ð' to "⇨", 'o' to "❍", 'p' to "□", 'ª' to "★", 'J' to "☺",
        )
    }
}
