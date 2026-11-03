package io.github.dishan18.books.office.drawing

import io.github.dishan18.books.office.XmlNode
import kotlin.math.roundToInt

/**
 * An Office theme: its twelve named colours, its heading and body fonts, and (for PowerPoint) the
 * fill, line and background styles shapes refer to by number. Colours are ARGB.
 */
class ThemeColors(
    val colors: Map<String, Int>,
    val majorFont: String?,
    val minorFont: String?,
    val fillStyles: List<XmlNode> = emptyList(),
    val lineStyles: List<XmlNode> = emptyList(),
    val backgroundFills: List<XmlNode> = emptyList(),
) {
    /** Excel's theme colour numbers list light before dark, unlike the theme itself. */
    fun excelIndex(index: Int): Int? = EXCEL_ORDER.getOrNull(index)?.let(colors::get)

    companion object {
        private val EXCEL_ORDER = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        private val SCHEME_NAMES = setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

        /** Office's default theme, for files that don't include one. */
        val DEFAULT = ThemeColors(
            colors = mapOf(
                "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
                "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(),
                "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(),
                "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
            ),
            majorFont = "Calibri Light",
            minorFont = "Calibri",
        )

        /** Reads a theme part (`a:theme`). */
        fun read(root: XmlNode): ThemeColors {
            val elements = root.child("themeElements") ?: return DEFAULT
            val scheme = elements.child("clrScheme")
            val colors = HashMap(DEFAULT.colors)
            scheme?.children?.forEach { node ->
                if (node.name !in SCHEME_NAMES) return@forEach
                val value = node.child("srgbClr")?.attr("val")?.let(::hex)
                    ?: node.child("sysClr")?.let { sys -> sys.attr("lastClr")?.let(::hex) ?: systemColor(sys.attr("val")) }
                if (value != null) colors[node.name] = value
            }
            val fonts = elements.child("fontScheme")
            val format = elements.child("fmtScheme")
            return ThemeColors(
                colors = colors,
                majorFont = fonts?.path("majorFont", "latin")?.attr("typeface")?.takeIf { it.isNotEmpty() } ?: DEFAULT.majorFont,
                minorFont = fonts?.path("minorFont", "latin")?.attr("typeface")?.takeIf { it.isNotEmpty() } ?: DEFAULT.minorFont,
                fillStyles = format?.child("fillStyleLst")?.children.orEmpty(),
                lineStyles = format?.child("lnStyleLst")?.children.orEmpty(),
                backgroundFills = format?.child("bgFillStyleLst")?.children.orEmpty(),
            )
        }

        fun hex(value: String): Int? = value.trim().takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }

        fun systemColor(name: String?): Int? = when (name) {
            "windowText", "menuText", "captionText", "btnText", "infoText" -> 0xFF000000.toInt()
            "window", "menu", "btnHighlight", "highlightText", "infoBk" -> 0xFFFFFFFF.toInt()
            "btnFace", "3dLight", "menuBar" -> 0xFFF0F0F0.toInt()
            "btnShadow", "grayText" -> 0xFFA0A0A0.toInt()
            "highlight", "hotLight", "menuHighlight" -> 0xFF0078D7.toInt()
            null -> null
            else -> null
        }

        /** Excel's tint: lightens (positive) or darkens (negative) a colour's luminance. */
        fun tint(argb: Int, tint: Double): Int {
            val hsl = toHsl(argb)
            hsl[2] = if (tint < 0) hsl[2] * (1 + tint).toFloat() else (hsl[2] * (1 - tint) + tint).toFloat()
            return fromHsl(hsl, argb ushr 24)
        }

        fun toHsl(argb: Int): FloatArray {
            val r = (argb shr 16 and 0xFF) / 255f
            val g = (argb shr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2f
            if (max == min) return floatArrayOf(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            val h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } / 6f
            return floatArrayOf(h, s, l)
        }

        fun fromHsl(hsl: FloatArray, alpha: Int = 0xFF): Int {
            val h = hsl[0]
            val s = hsl[1].coerceIn(0f, 1f)
            val l = hsl[2].coerceIn(0f, 1f)
            fun channel(t0: Float, p: Float, q: Float): Float {
                var t = t0
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                return when {
                    t < 1f / 6f -> p + (q - p) * 6f * t
                    t < 1f / 2f -> q
                    t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                    else -> p
                }
            }
            val (r, g, b) = if (s == 0f) {
                Triple(l, l, l)
            } else {
                val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
                val p = 2 * l - q
                Triple(channel(h + 1f / 3f, p, q), channel(h, p, q), channel(h - 1f / 3f, p, q))
            }
            return (alpha.coerceIn(0, 255) shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
        }
    }
}

/**
 * Resolves DrawingML colours (`srgbClr`, `schemeClr` with `lumMod`, `tint`, `alpha` and so on).
 * [map] is the slide's colour mapping (bg1 to lt1, tx1 to dk1, ...); `phClr` is the colour a theme
 * style was asked to use.
 */
class ColorResolver(private val theme: ThemeColors, private val map: Map<String, String>) {

    fun withMap(override: Map<String, String>?): ColorResolver = if (override.isNullOrEmpty()) this else ColorResolver(theme, map + override)

    fun scheme(name: String): Int? = theme.colors[map[name] ?: name] ?: theme.colors[DEFAULT_MAP[name] ?: name]

    /** The colour inside [container] (for example a `solidFill`), or null when it has none. */
    fun color(container: XmlNode?, placeholder: Int? = null): Int? {
        container ?: return null
        for (node in container.children) {
            val base = when (node.name) {
                "srgbClr" -> node.attr("val")?.let(ThemeColors::hex)
                "schemeClr" -> node.attr("val")?.let { if (it == "phClr") placeholder else scheme(it) }
                "sysClr" -> node.attr("lastClr")?.let(ThemeColors::hex) ?: ThemeColors.systemColor(node.attr("val"))
                "prstClr" -> PRESET_COLORS[node.attr("val")]
                "scrgbClr" -> scrgb(node)
                "hslClr" -> hslColor(node)
                else -> continue
            } ?: return null
            return modify(base, node)
        }
        return null
    }

    private fun scrgb(node: XmlNode): Int {
        fun c(name: String) = ((node.attr(name)?.toFloatOrNull() ?: 0f) / 100_000f).coerceIn(0f, 1f)
        // Linear light to sRGB.
        fun gamma(v: Float) = if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f)
        return (0xFF shl 24) or ((gamma(c("r")) * 255).roundToInt() shl 16) or ((gamma(c("g")) * 255).roundToInt() shl 8) or (gamma(c("b")) * 255).roundToInt()
    }

    private fun hslColor(node: XmlNode): Int {
        val h = (node.attr("hue")?.toFloatOrNull() ?: 0f) / 21_600_000f
        val s = (node.attr("sat")?.toFloatOrNull() ?: 0f) / 100_000f
        val l = (node.attr("lum")?.toFloatOrNull() ?: 0f) / 100_000f
        return ThemeColors.fromHsl(floatArrayOf(h, s, l))
    }

    /** Applies the modifiers inside a colour element, in order. */
    private fun modify(base: Int, node: XmlNode): Int {
        var color = base
        for (m in node.children) {
            val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100_000f
            color = when (m.name) {
                "lumMod" -> ThemeColors.toHsl(color).let { it[2] *= v; ThemeColors.fromHsl(it, color ushr 24) }
                "lumOff" -> ThemeColors.toHsl(color).let { it[2] += v; ThemeColors.fromHsl(it, color ushr 24) }
                "satMod" -> ThemeColors.toHsl(color).let { it[1] *= v; ThemeColors.fromHsl(it, color ushr 24) }
                "satOff" -> ThemeColors.toHsl(color).let { it[1] += v; ThemeColors.fromHsl(it, color ushr 24) }
                "hueOff" -> ThemeColors.toHsl(color).let { it[0] = ((it[0] + v * 100_000f / 21_600_000f) % 1f + 1f) % 1f; ThemeColors.fromHsl(it, color ushr 24) }
                "tint" -> mix(color, 0xFFFFFFFF.toInt(), v)
                "shade" -> mix(color, 0xFF000000.toInt(), v)
                "alpha" -> (color and 0x00FFFFFF) or ((v * 255).roundToInt().coerceIn(0, 255) shl 24)
                "alphaMod" -> (color and 0x00FFFFFF) or ((((color ushr 24) * v).roundToInt()).coerceIn(0, 255) shl 24)
                "inv" -> (color and 0xFF000000.toInt()) or (color.inv() and 0x00FFFFFF)
                "gray" -> {
                    val y = ((color shr 16 and 0xFF) * 0.299f + (color shr 8 and 0xFF) * 0.587f + (color and 0xFF) * 0.114f).roundToInt()
                    (color and 0xFF000000.toInt()) or (y shl 16) or (y shl 8) or y
                }
                else -> color
            }
        }
        return color
    }

    /** [amount] of [color] and the rest [other]: a 40% tint is 40% colour, 60% white. */
    private fun mix(color: Int, other: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * a + ((other shr shift) and 0xFF) * (1 - a)).roundToInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        val DEFAULT_MAP = mapOf(
            "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
            "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4",
            "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink",
        )

        /** A slide master's `clrMap`, or a layout's or slide's override of it. */
        fun clrMapOf(node: XmlNode?): Map<String, String> {
            node ?: return emptyMap()
            return DEFAULT_MAP.keys.mapNotNull { key -> node.attr(key)?.let { key to it } }.toMap()
        }

        private val PRESET_COLORS = mapOf(
            "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
            "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
            "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(), "silver" to 0xFFC0C0C0.toInt(), "orange" to 0xFFFFA500.toInt(),
            "purple" to 0xFF800080.toInt(), "navy" to 0xFF000080.toInt(), "maroon" to 0xFF800000.toInt(), "olive" to 0xFF808000.toInt(),
            "teal" to 0xFF008080.toInt(), "lime" to 0xFF00FF00.toInt(), "pink" to 0xFFFFC0CB.toInt(), "brown" to 0xFFA52A2A.toInt(),
            "gold" to 0xFFFFD700.toInt(), "darkGray" to 0xFFA9A9A9.toInt(), "lightGray" to 0xFFD3D3D3.toInt(), "dkGray" to 0xFFA9A9A9.toInt(),
            "ltGray" to 0xFFD3D3D3.toInt(), "darkBlue" to 0xFF00008B.toInt(), "dkBlue" to 0xFF00008B.toInt(), "lightBlue" to 0xFFADD8E6.toInt(),
            "ltBlue" to 0xFFADD8E6.toInt(), "darkRed" to 0xFF8B0000.toInt(), "dkRed" to 0xFF8B0000.toInt(), "darkGreen" to 0xFF006400.toInt(),
            "dkGreen" to 0xFF006400.toInt(), "lightGreen" to 0xFF90EE90.toInt(), "ltGreen" to 0xFF90EE90.toInt(),
        )
    }
}
