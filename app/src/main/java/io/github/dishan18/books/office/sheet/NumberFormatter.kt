package io.github.dishan18.books.office.sheet

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Shows a cell's number the way Excel does, from the cell's number format code ("#,##0.00",
 * "d-mmm-yy", "0%", ...). Dates are serial day numbers counted from 1900 (or 1904 when
 * [date1904]); month and day names come from [locale].
 */
class NumberFormatter(private val locale: Locale, private val date1904: Boolean = false) {

    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val parsed = HashMap<String, List<Section>>()

    /** The local short date pattern with a four-digit year, as Excel shows built-in format 14. */
    private val shortDatePattern: String by lazy {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        val years = Regex("y+").find(pattern)?.value
        // Turn it into a format code: java's M (month) is m, y stays y, d stays d.
        val code = if (years != null && years.length < 4) pattern.replaceFirst(years, "yyyy") else pattern
        code.replace("M", "m").replace("L", "m")
    }

    fun format(value: Double, code: String): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        val sections = parsed.getOrPut(code) { parse(resolveBuiltIn(code)) }
        val (section, useAbs) = pick(sections, value) ?: return general(value)
        return when (section.kind) {
            Kind.GENERAL -> section.render(general(if (useAbs) abs(value) else value))
            Kind.TEXT -> general(value)
            Kind.DATE -> formatDate(section, value)
            Kind.NUMBER -> formatNumber(section, value, useAbs)
            Kind.LITERAL -> (if (value < 0 && !useAbs) "-" else "") + section.render("")
        }
    }

    /** True when [code] shows a date or time, so a cell with it holds a date. */
    fun isDate(code: String): Boolean = parsed.getOrPut(code) { parse(resolveBuiltIn(code)) }.firstOrNull()?.kind == Kind.DATE

    private fun resolveBuiltIn(code: String): String = when (code) {
        SHORT_DATE -> shortDatePattern
        SHORT_DATE_TIME -> "$shortDatePattern h:mm"
        else -> code
    }

    // Choosing a section

    /** Picks the section for [value]; the second result says the section shows the value without its sign. */
    private fun pick(sections: List<Section>, value: Double): Pair<Section, Boolean>? {
        if (sections.isEmpty()) return null
        val numeric = sections.take(3)
        if (numeric.any { it.condition != null }) {
            // With conditions, the first section whose condition holds wins; one without a condition catches the rest.
            numeric.forEach { s ->
                val c = s.condition
                // A section kept for negative numbers ("[<0]") shows them without their sign.
                if (c == null || c.matches(value)) return s to (c != null && value < 0 && c.op.startsWith("<") && c.value <= 0)
            }
            return null
        }
        return when {
            value > 0 -> numeric[0] to false
            value < 0 -> if (numeric.size >= 2) numeric[1] to true else numeric[0] to false
            else -> if (numeric.size >= 3) numeric[2] to false else numeric[0] to false
        }
    }

    // General

    /** Excel's General: as many digits as fit in 11 characters, switching to scientific notation when none do. */
    fun general(value: Double): String {
        if (value == 0.0) return "0"
        if (abs(value) < 1e11) {
            for (digits in 11 downTo 1) {
                val text = BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
                if (text.trimStart('-').length <= 11) return localizeDecimal(text)
            }
        }
        return scientific(value, 5, trim = true, exponentDigits = 2, plusSign = true)
    }

    private fun localizeDecimal(text: String) = if (symbols.decimalSeparator == '.') text else text.replace('.', symbols.decimalSeparator)

    private fun scientific(value: Double, decimals: Int, trim: Boolean, exponentDigits: Int, plusSign: Boolean): String {
        if (value == 0.0) return "0"
        var exponent = floor(log10(abs(value))).toInt()
        var mantissa = BigDecimal(value).divide(BigDecimal.TEN.pow(exponent, MathContext.DECIMAL128), MathContext.DECIMAL128)
            .setScale(decimals, RoundingMode.HALF_UP)
        if (mantissa.abs() >= BigDecimal.TEN) {
            exponent++
            mantissa = mantissa.divide(BigDecimal.TEN).setScale(decimals, RoundingMode.HALF_UP)
        }
        var m = mantissa.toPlainString()
        if (trim && '.' in m) m = m.trimEnd('0').trimEnd('.')
        val sign = if (exponent < 0) "-" else if (plusSign) "+" else ""
        return localizeDecimal(m) + "E" + sign + abs(exponent).toString().padStart(exponentDigits, '0')
    }

    // Numbers

    private fun formatNumber(section: Section, value: Double, useAbs: Boolean): String {
        val spec = section.number!!
        var v = if (useAbs) abs(value) else value
        repeat(spec.percent) { v *= 100 }
        repeat(spec.scaling) { v /= 1000 }
        return when {
            spec.exponent != null -> formatScientific(section, spec, v)
            spec.fraction != null -> formatFraction(section, spec, v)
            else -> formatDecimal(section, spec, v)
        }
    }

    private fun formatDecimal(section: Section, spec: NumberSpec, v: Double): String {
        val rounded = BigDecimal(v).round(MathContext(15, RoundingMode.HALF_UP)).setScale(spec.fractionPlaces.size, RoundingMode.HALF_UP)
        // Like Excel, a small negative number keeps its sign even when it rounds to zero (-0.00).
        val negative = v < 0
        val digits = rounded.abs().toPlainString()
        val integerText = digits.substringBefore('.').trimStart('0')
        val fractionText = digits.substringAfter('.', "")
        val out = StringBuilder()
        if (negative) out.append('-')
        val integerOut = fillInteger(spec, integerText)
        val fractionOut = fillFraction(spec.fractionPlaces, fractionText)
        var integerIndex = 0
        var fractionIndex = 0
        section.tokens.forEach { token ->
            when (token.type) {
                TokenType.INT_DIGIT -> out.append(integerOut[integerIndex++])
                TokenType.FRAC_DIGIT -> out.append(fractionOut[fractionIndex++])
                TokenType.DECIMAL -> {
                    // ".00" has no integer placeholders, but 12.5 still shows as 12.50.
                    if (spec.integerPlaces.isEmpty()) out.append(integerText)
                    out.append(symbols.decimalSeparator)
                }
                TokenType.PERCENT -> out.append('%')
                TokenType.LITERAL -> out.append(token.text)
                else -> Unit
            }
        }
        return out.toString()
    }

    /** What each integer placeholder shows, right to left from the last; extra digits go to the first. */
    private fun fillInteger(spec: NumberSpec, digits: String): List<String> {
        val places = spec.integerPlaces
        if (places.isEmpty()) return emptyList()
        val result = MutableList(places.size) { "" }
        if (spec.grouping) {
            val minimum = places.count { it == '0' }
            val padded = digits.padStart(minimum, '0')
            val grouped = StringBuilder()
            padded.forEachIndexed { i, c ->
                if (i > 0 && (padded.length - i) % 3 == 0) grouped.append(symbols.groupingSeparator)
                grouped.append(c)
            }
            val spaces = places.count { it == '?' } - (padded.length - minimum).coerceAtLeast(0)
            result[0] = " ".repeat(spaces.coerceAtLeast(0)) + grouped
            return result
        }
        var remaining = digits
        for (i in places.indices.reversed()) {
            if (i == 0) {
                result[0] = if (remaining.isNotEmpty()) remaining else placeholderBlank(places[0])
            } else if (remaining.isNotEmpty()) {
                result[i] = remaining.last().toString()
                remaining = remaining.dropLast(1)
            } else {
                result[i] = placeholderBlank(places[i])
            }
        }
        return result
    }

    private fun placeholderBlank(place: Char) = when (place) {
        '0' -> "0"
        '?' -> " "
        else -> ""
    }

    /** Decimal places: zeros always show; # drops trailing zeros; ? turns them into spaces. */
    private fun fillFraction(places: List<Char>, digits: String): List<String> {
        val result = MutableList(places.size) { i -> digits.getOrElse(i) { '0' }.toString() }
        for (i in places.indices.reversed()) {
            if (result[i] != "0" || places[i] == '0') break
            result[i] = if (places[i] == '?') " " else ""
        }
        return result
    }

    private fun formatScientific(section: Section, spec: NumberSpec, v: Double): String {
        val exp = spec.exponent!!
        val integerCount = spec.integerPlaces.size.coerceAtLeast(1)
        val decimals = spec.fractionPlaces.size
        val out = StringBuilder()
        var mantissaText: String
        var exponent: Int
        if (v == 0.0) {
            mantissaText = "0" + if (decimals > 0) "." + "0".repeat(decimals) else ""
            exponent = 0
        } else {
            exponent = floor(log10(abs(v))).toInt()
            // "##0.0E+0" keeps the exponent a multiple of three (engineering notation).
            if (integerCount > 1 && spec.integerPlaces.first() == '#') exponent = Math.floorDiv(exponent, integerCount) * integerCount
            var mantissa = BigDecimal(abs(v)).divide(BigDecimal.TEN.pow(exponent, MathContext.DECIMAL128), MathContext.DECIMAL128)
                .setScale(decimals, RoundingMode.HALF_UP)
            val limit = if (integerCount > 1 && spec.integerPlaces.first() == '#') BigDecimal.TEN.pow(integerCount) else BigDecimal.TEN
            if (mantissa >= limit) {
                exponent += if (limit == BigDecimal.TEN) 1 else integerCount
                mantissa = BigDecimal(abs(v)).divide(BigDecimal.TEN.pow(exponent, MathContext.DECIMAL128), MathContext.DECIMAL128)
                    .setScale(decimals, RoundingMode.HALF_UP)
            }
            mantissaText = mantissa.toPlainString()
        }
        if (v < 0) out.append('-')
        val integerOut = fillInteger(spec.copy(grouping = false), mantissaText.substringBefore('.').trimStart('0'))
        val fractionOut = fillFraction(spec.fractionPlaces, mantissaText.substringAfter('.', ""))
        var integerIndex = 0
        var fractionIndex = 0
        var exponentWritten = false
        section.tokens.forEach { token ->
            when (token.type) {
                TokenType.INT_DIGIT -> out.append(integerOut[integerIndex++])
                TokenType.FRAC_DIGIT -> out.append(fractionOut[fractionIndex++])
                TokenType.DECIMAL -> out.append(symbols.decimalSeparator)
                TokenType.PERCENT -> out.append('%')
                TokenType.LITERAL -> out.append(token.text)
                TokenType.EXPONENT -> if (!exponentWritten) {
                    exponentWritten = true
                    val sign = if (exponent < 0) "-" else if (exp.plus) "+" else ""
                    out.append(if (token.text.startsWith("e")) 'e' else 'E').append(sign)
                        .append(abs(exponent).toString().padStart(exp.digits, '0'))
                }
                else -> Unit
            }
        }
        return out.toString()
    }

    private fun formatFraction(section: Section, spec: NumberSpec, v: Double): String {
        val f = spec.fraction!!
        val negative = v < 0
        val a = abs(v)
        val whole = if (f.wholePlaces > 0) floor(a).toLong() else 0L
        val rest = a - whole
        val (numerator, denominator) = if (f.fixedDenominator != null) {
            (rest * f.fixedDenominator).roundToLong() to f.fixedDenominator.toLong()
        } else {
            bestFraction(rest, 10.0.pow(f.denominatorDigits).toLong() - 1)
        }
        var w = whole
        var n = numerator
        if (n == denominator && f.wholePlaces > 0) {
            w++
            n = 0
        }
        val sb = StringBuilder()
        if (negative && (w != 0L || n != 0L)) sb.append('-')
        val wholeText = if (f.wholePlaces > 0) (if (w == 0L && n != 0L) "" else w.toString()) else ""
        val nText = if (n == 0L && f.wholePlaces > 0) "" else n.toString()
        val dText = if (n == 0L && f.wholePlaces > 0) "" else denominator.toString()
        var fractionDone = false
        var wholeDone = false
        section.tokens.forEach { token ->
            when (token.type) {
                TokenType.WHOLE -> if (!wholeDone) {
                    wholeDone = true
                    sb.append(wholeText)
                }
                TokenType.NUMERATOR, TokenType.DENOMINATOR -> if (!fractionDone) {
                    fractionDone = true
                    if (nText.isEmpty()) {
                        // Excel leaves the fraction's width blank when there's no fraction part.
                        sb.append(" ".repeat(f.numeratorDigits + 1 + f.denominatorWidth))
                    } else {
                        sb.append(nText.padStart(f.numeratorDigits)).append('/').append(dText.padEnd(f.denominatorWidth))
                    }
                }
                TokenType.LITERAL -> if (!(fractionDone && token.text == "/")) sb.append(token.text)
                TokenType.PERCENT -> sb.append('%')
                else -> Unit
            }
        }
        return sb.toString().trimEnd().ifEmpty { "0" }
    }

    /** The closest fraction to [x] (0..1) with a denominator up to [maxDenominator]. */
    private fun bestFraction(x: Double, maxDenominator: Long): Pair<Long, Long> {
        if (x <= 0.0) return 0L to 1L
        var bestN = 0L
        var bestD = 1L
        var bestError = x
        for (d in 1..maxDenominator.coerceIn(1, 9999)) {
            val n = (x * d).roundToLong()
            val error = abs(x - n.toDouble() / d)
            if (error < bestError - 1e-12) {
                bestN = n
                bestD = d
                bestError = error
                if (error < 1e-12) break
            }
        }
        return bestN to bestD
    }

    // Dates

    private fun formatDate(section: Section, value: Double): String {
        if (value < 0 || value > MAX_SERIAL) return "########"
        val subsecondDigits = section.tokens.firstOrNull { it.type == TokenType.SUBSECOND }?.text?.length ?: 0
        val unit = 10.0.pow(3 - subsecondDigits.coerceAtMost(3))
        // Round to what is shown, as Excel does (12:59:59.6 shows as 13:00:00).
        var totalMs = (value * MS_PER_DAY / unit).roundToLong() * unit.toLong()
        val days = Math.floorDiv(totalMs, MS_PER_DAY.toLong())
        val msOfDay = totalMs - days * MS_PER_DAY.toLong()
        totalMs = days * MS_PER_DAY.toLong() + msOfDay

        val date = dateOf(days)
        val hour = (msOfDay / 3_600_000).toInt()
        val minute = ((msOfDay / 60_000) % 60).toInt()
        val second = ((msOfDay / 1000) % 60).toInt()
        val millis = (msOfDay % 1000).toInt()
        val twelveHour = section.tokens.any { it.type == TokenType.AMPM }

        val out = StringBuilder()
        section.tokens.forEach { t ->
            val n = t.text.length
            when (t.type) {
                TokenType.YEAR -> out.append(if (n <= 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString())
                TokenType.MONTH -> out.append(
                    when (n) {
                        1 -> date.month.toString()
                        2 -> date.month.toString().padStart(2, '0')
                        3 -> date.monthName(TextStyle.SHORT, locale)
                        5 -> date.monthName(TextStyle.NARROW, locale)
                        else -> date.monthName(TextStyle.FULL, locale)
                    },
                )
                TokenType.DAY -> out.append(
                    when (n) {
                        1 -> date.day.toString()
                        2 -> date.day.toString().padStart(2, '0')
                        3 -> date.weekdayName(TextStyle.SHORT, locale)
                        else -> date.weekdayName(TextStyle.FULL, locale)
                    },
                )
                TokenType.HOUR -> {
                    val h = if (twelveHour) (hour % 12).let { if (it == 0) 12 else it } else hour
                    out.append(if (n >= 2) h.toString().padStart(2, '0') else h.toString())
                }
                TokenType.MINUTE -> out.append(if (n >= 2) minute.toString().padStart(2, '0') else minute.toString())
                TokenType.SECOND -> out.append(if (n >= 2) second.toString().padStart(2, '0') else second.toString())
                TokenType.SUBSECOND -> out.append(symbols.decimalSeparator).append(millis.toString().padStart(3, '0').take(n))
                TokenType.ELAPSED_HOURS -> out.append((totalMs / 3_600_000).toString().padStart(n, '0'))
                TokenType.ELAPSED_MINUTES -> out.append((totalMs / 60_000).toString().padStart(n, '0'))
                TokenType.ELAPSED_SECONDS -> out.append((totalMs / 1000).toString().padStart(n, '0'))
                TokenType.AMPM -> {
                    val pm = hour >= 12
                    out.append(
                        when (t.text.length) {
                            3 -> (if (pm) t.text.substringAfter('/') else t.text.substringBefore('/'))
                            else -> {
                                val am = t.text.substring(0, 2)
                                val pmText = t.text.substring(3, 5)
                                if (pm) pmText else am
                            }
                        },
                    )
                }
                TokenType.LITERAL -> out.append(t.text)
                else -> Unit
            }
        }
        return out.toString()
    }

    /** Excel's calendar, including its 29 February 1900 (serial 60), kept for compatibility with Lotus 1-2-3. */
    private fun dateOf(days: Long): ExcelDate {
        if (date1904) return ExcelDate.of(LocalDate.of(1904, 1, 1).plusDays(days))
        return when {
            days == 0L -> ExcelDate(1900, 1, 0, null)
            days == 60L -> ExcelDate(1900, 2, 29, null)
            days < 60 -> ExcelDate.of(LocalDate.of(1899, 12, 31).plusDays(days))
            else -> ExcelDate.of(LocalDate.of(1899, 12, 30).plusDays(days))
        }
    }

    private data class ExcelDate(val year: Int, val month: Int, val day: Int, val date: LocalDate?) {
        fun monthName(style: TextStyle, locale: Locale): String = java.time.Month.of(month).getDisplayName(style, locale)

        fun weekdayName(style: TextStyle, locale: Locale): String =
            (date ?: LocalDate.of(1900, 2, 28)).dayOfWeek.getDisplayName(style, locale)

        companion object {
            fun of(date: LocalDate) = ExcelDate(date.year, date.monthValue, date.dayOfMonth, date)
        }
    }

    // Parsing format codes

    private enum class Kind { GENERAL, NUMBER, DATE, TEXT, LITERAL }

    private enum class TokenType {
        LITERAL, GENERAL, TEXT, INT_DIGIT, FRAC_DIGIT, DECIMAL, COMMA, PERCENT, EXPONENT, EXP_DIGIT,
        WHOLE, NUMERATOR, DENOMINATOR,
        YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, SUBSECOND, ELAPSED_HOURS, ELAPSED_MINUTES, ELAPSED_SECONDS, AMPM,
    }

    private data class Token(val type: TokenType, val text: String)

    private data class Condition(val op: String, val value: Double) {
        fun matches(v: Double) = when (op) {
            "<" -> v < value
            "<=" -> v <= value
            ">" -> v > value
            ">=" -> v >= value
            "=" -> v == value
            "<>" -> v != value
            else -> false
        }
    }

    private data class ExponentSpec(val plus: Boolean, val digits: Int)

    private data class FractionSpec(val wholePlaces: Int, val numeratorDigits: Int, val denominatorDigits: Int, val fixedDenominator: Int?) {
        val denominatorWidth get() = fixedDenominator?.toString()?.length ?: denominatorDigits
    }

    private data class NumberSpec(
        val integerPlaces: List<Char>,
        val fractionPlaces: List<Char>,
        val grouping: Boolean,
        val scaling: Int,
        val percent: Int,
        val exponent: ExponentSpec?,
        val fraction: FractionSpec?,
    )

    private class Section(val tokens: List<Token>, val kind: Kind, val condition: Condition?, val number: NumberSpec?) {
        /** For General and literal-only sections: the tokens with [general] in place of "General". */
        fun render(general: String): String = buildString {
            tokens.forEach {
                when (it.type) {
                    TokenType.GENERAL -> append(general)
                    TokenType.LITERAL -> append(it.text)
                    TokenType.PERCENT -> append('%')
                    else -> Unit
                }
            }
        }
    }

    private fun parse(code: String): List<Section> = splitSections(code).map(::parseSection)

    private fun splitSections(code: String): List<String> {
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length - 1 else it }
                    current.append(code, i, end + 1)
                    i = end
                }
                c == '\\' && i + 1 < code.length -> {
                    current.append(c).append(code[i + 1])
                    i++
                }
                c == '[' -> {
                    val end = code.indexOf(']', i + 1).let { if (it < 0) code.length - 1 else it }
                    current.append(code, i, end + 1)
                    i = end
                }
                c == ';' -> {
                    sections += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        sections += current.toString()
        return sections
    }

    private fun parseSection(source: String): Section {
        val raw = mutableListOf<Token>()
        var condition: Condition? = null
        var i = 0
        val lower = source.lowercase()
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> {
                    val end = source.indexOf('"', i + 1).let { if (it < 0) source.length else it }
                    raw += Token(TokenType.LITERAL, source.substring(i + 1, end))
                    i = end + 1
                }
                c == '\\' -> {
                    if (i + 1 < source.length) raw += Token(TokenType.LITERAL, source[i + 1].toString())
                    i += 2
                }
                c == '_' -> {
                    raw += Token(TokenType.LITERAL, " ")
                    i += 2
                }
                c == '*' -> i += 2
                c == '[' -> {
                    val end = source.indexOf(']', i + 1).let { if (it < 0) source.length else it }
                    val inner = source.substring(i + 1, end)
                    val innerLower = inner.lowercase()
                    when {
                        inner.startsWith("$") -> inner.drop(1).substringBefore('-').takeIf { it.isNotEmpty() }?.let { raw += Token(TokenType.LITERAL, it) }
                        innerLower.matches(Regex("h+")) -> raw += Token(TokenType.ELAPSED_HOURS, inner)
                        innerLower.matches(Regex("m+")) -> raw += Token(TokenType.ELAPSED_MINUTES, inner)
                        innerLower.matches(Regex("s+")) -> raw += Token(TokenType.ELAPSED_SECONDS, inner)
                        CONDITION.matches(inner) -> {
                            val m = CONDITION.find(inner)!!
                            condition = Condition(m.groupValues[1], m.groupValues[2].toDouble())
                        }
                        else -> Unit // A colour such as [Red] or [Color10].
                    }
                    i = end + 1
                }
                lower.startsWith("general", i) -> {
                    raw += Token(TokenType.GENERAL, "General")
                    i += 7
                }
                lower.startsWith("am/pm", i) -> {
                    raw += Token(TokenType.AMPM, source.substring(i, i + 5))
                    i += 5
                }
                lower.startsWith("a/p", i) -> {
                    raw += Token(TokenType.AMPM, source.substring(i, i + 3))
                    i += 3
                }
                c.lowercaseChar() in "ymdhse" && !(c.lowercaseChar() == 'e' && i + 1 < source.length && source[i + 1] in "+-") -> {
                    var j = i
                    while (j < source.length && source[j].lowercaseChar() == c.lowercaseChar()) j++
                    val text = source.substring(i, j).lowercase()
                    raw += Token(
                        when (c.lowercaseChar()) {
                            'y', 'e' -> TokenType.YEAR
                            'm' -> TokenType.MONTH
                            'd' -> TokenType.DAY
                            'h' -> TokenType.HOUR
                            else -> TokenType.SECOND
                        },
                        if (c.lowercaseChar() == 'e') "yyyy" else text,
                    )
                    i = j
                }
                (c == 'E' || c == 'e') && i + 1 < source.length && source[i + 1] in "+-" -> {
                    raw += Token(TokenType.EXPONENT, source.substring(i, i + 2))
                    i += 2
                }
                c in "0#?" -> {
                    raw += Token(TokenType.INT_DIGIT, c.toString())
                    i++
                }
                c == '.' -> {
                    raw += Token(TokenType.DECIMAL, ".")
                    i++
                }
                c == ',' -> {
                    raw += Token(TokenType.COMMA, ",")
                    i++
                }
                c == '%' -> {
                    raw += Token(TokenType.PERCENT, "%")
                    i++
                }
                c == '@' -> {
                    raw += Token(TokenType.TEXT, "@")
                    i++
                }
                c == '/' -> {
                    raw += Token(TokenType.LITERAL, "/")
                    i++
                }
                c.isDigit() -> {
                    raw += Token(TokenType.LITERAL, c.toString())
                    i++
                }
                else -> {
                    raw += Token(TokenType.LITERAL, c.toString())
                    i++
                }
            }
        }

        val isDate = raw.any {
            it.type in setOf(
                TokenType.YEAR, TokenType.MONTH, TokenType.DAY, TokenType.HOUR, TokenType.SECOND,
                TokenType.ELAPSED_HOURS, TokenType.ELAPSED_MINUTES, TokenType.ELAPSED_SECONDS, TokenType.AMPM,
            )
        }
        return when {
            isDate -> Section(dateTokens(raw), Kind.DATE, condition, null)
            raw.any { it.type == TokenType.INT_DIGIT } -> numberSection(raw, condition)
            raw.any { it.type == TokenType.GENERAL } -> Section(literalize(raw), Kind.GENERAL, condition, null)
            raw.any { it.type == TokenType.TEXT } -> Section(raw, Kind.TEXT, condition, null)
            else -> Section(literalize(raw), Kind.LITERAL, condition, null)
        }
    }

    /** Outside number sections, punctuation that looks like a placeholder is just text. */
    private fun literalize(tokens: List<Token>) = tokens.map {
        when (it.type) {
            TokenType.DECIMAL, TokenType.COMMA -> Token(TokenType.LITERAL, it.text)
            TokenType.EXPONENT -> Token(TokenType.LITERAL, it.text)
            else -> it
        }
    }

    /** Settles m (month or minute), fractional seconds, and turns stray number tokens into text. */
    private fun dateTokens(raw: List<Token>): List<Token> {
        val tokens = raw.toMutableList()
        fun neighbour(from: Int, step: Int): Token? {
            var j = from + step
            while (j in tokens.indices) {
                val t = tokens[j]
                if (t.type != TokenType.LITERAL && t.type != TokenType.DECIMAL && t.type != TokenType.COMMA) return t
                j += step
            }
            return null
        }
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type == TokenType.MONTH && t.text.length <= 2) {
                val before = neighbour(i, -1)?.type
                val after = neighbour(i, +1)?.type
                if (before == TokenType.HOUR || before == TokenType.ELAPSED_HOURS || after == TokenType.SECOND || after == TokenType.ELAPSED_SECONDS) {
                    tokens[i] = Token(TokenType.MINUTE, t.text)
                }
            }
        }
        val out = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.DECIMAL && out.lastOrNull()?.type in setOf(TokenType.SECOND, TokenType.ELAPSED_SECONDS) &&
                i + 1 < tokens.size && tokens[i + 1].type == TokenType.INT_DIGIT && tokens[i + 1].text == "0"
            ) {
                var j = i + 1
                while (j < tokens.size && tokens[j].type == TokenType.INT_DIGIT && tokens[j].text == "0") j++
                out += Token(TokenType.SUBSECOND, "0".repeat(j - i - 1))
                i = j
                continue
            }
            out += when (t.type) {
                TokenType.INT_DIGIT, TokenType.DECIMAL, TokenType.COMMA, TokenType.EXPONENT, TokenType.GENERAL, TokenType.TEXT ->
                    Token(TokenType.LITERAL, t.text)
                else -> t
            }
            i++
        }
        return out
    }

    private fun numberSection(raw: List<Token>, condition: Condition?): Section {
        val tokens = raw.toMutableList()
        val firstDigit = tokens.indexOfFirst { it.type == TokenType.INT_DIGIT }
        val lastDigit = tokens.indexOfLast { it.type == TokenType.INT_DIGIT }
        val exponentAt = tokens.indexOfFirst { it.type == TokenType.EXPONENT }
        // A fraction: a slash after a placeholder, followed by placeholders or a fixed denominator ("?/16").
        val slashAt = tokens.withIndex().indexOfFirst { (i, t) ->
            val next = tokens.getOrNull(i + 1)
            t.type == TokenType.LITERAL && t.text == "/" && i > firstDigit &&
                (next?.type == TokenType.INT_DIGIT || (next?.type == TokenType.LITERAL && next.text.length == 1 && next.text[0].isDigit()))
        }

        // Only the first point is a decimal point; others (outside a fraction) are text.
        var decimalAt = -1
        tokens.indices.forEach { i ->
            if (tokens[i].type == TokenType.DECIMAL) {
                if (decimalAt < 0 && slashAt < 0 && (exponentAt < 0 || i < exponentAt)) decimalAt = i else tokens[i] = Token(TokenType.LITERAL, ".")
            }
        }

        if (slashAt >= 0) return fractionSection(tokens, slashAt, condition)

        val integerPlaces = mutableListOf<Char>()
        val fractionPlaces = mutableListOf<Char>()
        var exponentDigits = 0
        var grouping = false
        var scaling = 0
        var percent = 0
        val mantissaEnd = if (exponentAt >= 0) exponentAt else tokens.size
        val lastMantissaDigit = (0 until mantissaEnd).lastOrNull { tokens[it].type == TokenType.INT_DIGIT } ?: -1
        tokens.indices.forEach { i ->
            val t = tokens[i]
            when (t.type) {
                TokenType.INT_DIGIT -> when {
                    exponentAt in 0 until i -> {
                        exponentDigits++
                        tokens[i] = Token(TokenType.EXP_DIGIT, t.text)
                    }
                    decimalAt in 0 until i -> {
                        fractionPlaces += t.text[0]
                        tokens[i] = Token(TokenType.FRAC_DIGIT, t.text)
                    }
                    else -> integerPlaces += t.text[0]
                }
                TokenType.COMMA -> {
                    val digitAfterInIntegerPart = ((i + 1) until (if (decimalAt >= 0) decimalAt else mantissaEnd)).any { tokens[it].type == TokenType.INT_DIGIT }
                    when {
                        i > lastMantissaDigit || (decimalAt in 0 until i && !digitAfterInIntegerPart) -> scaling++
                        digitAfterInIntegerPart && i > firstDigit -> grouping = true
                        else -> Unit
                    }
                }
                TokenType.PERCENT -> percent++
                else -> Unit
            }
        }
        // A comma right before the decimal point scales too ("#,##0,.0" is thousands).
        if (decimalAt > 0 && tokens[decimalAt - 1].type == TokenType.COMMA && scaling == 0 && !grouping) scaling = 1
        val exponent = if (exponentAt >= 0) ExponentSpec(plus = tokens[exponentAt].text.endsWith("+"), digits = exponentDigits.coerceAtLeast(1)) else null
        val spec = NumberSpec(integerPlaces, fractionPlaces, grouping, scaling, percent, exponent, null)
        return Section(tokens.map { if (it.type == TokenType.GENERAL) Token(TokenType.LITERAL, "") else it }, Kind.NUMBER, condition, spec)
    }

    private fun fractionSection(tokens: MutableList<Token>, slashAt: Int, condition: Condition?): Section {
        // Numerator: the run of placeholders right before the slash. Whole part: placeholders before that, if any.
        var numeratorStart = slashAt
        while (numeratorStart > 0 && tokens[numeratorStart - 1].type == TokenType.INT_DIGIT) numeratorStart--
        val numeratorDigits = slashAt - numeratorStart
        var wholePlaces = 0
        for (i in 0 until numeratorStart) {
            if (tokens[i].type == TokenType.INT_DIGIT) {
                wholePlaces++
                tokens[i] = Token(TokenType.WHOLE, tokens[i].text)
            }
        }
        for (i in numeratorStart until slashAt) tokens[i] = Token(TokenType.NUMERATOR, tokens[i].text)
        // Denominator: placeholders ("??" means up to two digits) or a fixed number ("16", "100").
        var denominatorEnd = slashAt + 1
        val denominator = StringBuilder()
        while (denominatorEnd < tokens.size) {
            val t = tokens[denominatorEnd]
            val isPart = t.type == TokenType.INT_DIGIT || (t.type == TokenType.LITERAL && t.text.length == 1 && t.text[0].isDigit())
            if (!isPart) break
            denominator.append(t.text)
            tokens[denominatorEnd] = Token(TokenType.DENOMINATOR, t.text)
            denominatorEnd++
        }
        tokens[slashAt] = Token(TokenType.DENOMINATOR, "/")
        val text = denominator.toString()
        val fixedDenominator = text.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull()
        val denominatorDigits = text.count { it in "0#?" }
        val spec = FractionSpec(wholePlaces, numeratorDigits.coerceAtLeast(1), denominatorDigits.coerceAtLeast(1), fixedDenominator)
        val percent = tokens.count { it.type == TokenType.PERCENT }
        return Section(
            tokens,
            Kind.NUMBER,
            condition,
            NumberSpec(emptyList(), emptyList(), grouping = false, scaling = 0, percent = percent, exponent = null, fraction = spec),
        )
    }

    companion object {
        /** Built-in format 14, the local short date. */
        const val SHORT_DATE = "\u0000shortDate"

        /** Built-in format 22, the local short date with time. */
        const val SHORT_DATE_TIME = "\u0000shortDateTime"

        private const val MS_PER_DAY = 86_400_000.0
        private const val MAX_SERIAL = 2_958_465.999_999 // 31 December 9999
        private val CONDITION = Regex("^(<=|>=|<>|<|>|=)\\s*(-?\\d+(?:\\.\\d+)?)$")

        /** Excel's built-in number formats, by id. Ids not listed show as General. */
        val builtIn: Map<Int, String> = buildMap {
            put(0, "General")
            put(1, "0")
            put(2, "0.00")
            put(3, "#,##0")
            put(4, "#,##0.00")
            put(5, "\"$\"#,##0_);(\"$\"#,##0)")
            put(6, "\"$\"#,##0_);[Red](\"$\"#,##0)")
            put(7, "\"$\"#,##0.00_);(\"$\"#,##0.00)")
            put(8, "\"$\"#,##0.00_);[Red](\"$\"#,##0.00)")
            put(9, "0%")
            put(10, "0.00%")
            put(11, "0.00E+00")
            put(12, "# ?/?")
            put(13, "# ??/??")
            put(14, SHORT_DATE)
            put(15, "d-mmm-yy")
            put(16, "d-mmm")
            put(17, "mmm-yy")
            put(18, "h:mm AM/PM")
            put(19, "h:mm:ss AM/PM")
            put(20, "h:mm")
            put(21, "h:mm:ss")
            put(22, SHORT_DATE_TIME)
            // East Asian date formats; shown as the local short date.
            (27..36).forEach { put(it, SHORT_DATE) }
            (50..58).forEach { put(it, SHORT_DATE) }
            put(37, "#,##0 ;(#,##0)")
            put(38, "#,##0 ;[Red](#,##0)")
            put(39, "#,##0.00;(#,##0.00)")
            put(40, "#,##0.00;[Red](#,##0.00)")
            put(45, "mm:ss")
            put(46, "[h]:mm:ss")
            put(47, "mmss.0")
            put(48, "##0.0E+0")
            put(49, "@")
        }
    }
}
