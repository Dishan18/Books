package io.github.dishan18.books.manga.util

/**
 * Orders strings the way people expect file names to sort: "page2" before "page10",
 * case-insensitively, with leading zeros ignored for comparison.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                // Equal whole numbers: a decimal part ("2.5") sorts after none ("2", "2.cbz").
                val fracA = fraction(a, i)
                val fracB = fraction(b, j)
                if (fracA != null || fracB != null) {
                    if (fracA == null) return -1
                    if (fracB == null) return 1
                    val width = maxOf(fracA.length, fracB.length)
                    val fracCmp = fracA.padEnd(width, '0').compareTo(fracB.padEnd(width, '0'))
                    if (fracCmp != 0) return fracCmp
                    i += fracA.length + 1
                    j += fracB.length + 1
                }
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val remaining = (a.length - i) - (b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    /** The digits of a decimal part starting with a '.' at [index] ("2.5" → "5"), or null if there is none. */
    private fun fraction(s: String, index: Int): String? {
        if (index >= s.length - 1 || s[index] != '.' || !s[index + 1].isDigit()) return null
        var end = index + 1
        while (end < s.length && s[end].isDigit()) end++
        return s.substring(index + 1, end)
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heif", "heic")

fun isImageName(name: String): Boolean {
    val base = name.substringAfterLast('/')
    if (base.startsWith('.') || name.startsWith("__MACOSX/")) return false
    return base.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
}
