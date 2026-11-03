package io.github.dishan18.books.office

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** A problem with an Excel or PowerPoint file, worded for the person who tried to open it. */
class OfficeFileException(message: String) : IOException(message)

/** A link from one part of the file to another (or, when [external], to a URL that is never fetched). */
data class Relationship(val id: String, val type: String, val target: String, val external: Boolean) {
    /** Relationship types differ between the usual and the "strict" variant of the format; the last segment doesn't. */
    fun isType(name: String) = type.substringAfterLast('/') == name
}

/**
 * An Excel or PowerPoint file: a ZIP of XML parts. Every read is bounded, so a hostile file (a
 * "zip bomb", a giant part, DTD entity tricks) can't exhaust memory, and nothing outside the file
 * is ever read or fetched.
 */
class OfficePackage(file: File) : Closeable {

    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: ZipException) {
        throw OfficeFileException(NOT_OFFICE)
    }

    private val entries: Map<String, ZipEntry>
    private val lowercaseEntries: Map<String, ZipEntry>

    init {
        if (zip.size() > MAX_ENTRIES) {
            zip.close()
            throw OfficeFileException("This file has too many parts to open.")
        }
        entries = zip.entries().asSequence().filterNot { it.isDirectory }.associateBy { it.name.trimStart('/') }
        // Part names are case-insensitive in the format, and some apps write links in a different case.
        lowercaseEntries = entries.mapKeys { it.key.lowercase() }
    }

    private fun entry(path: String): ZipEntry? = entries[path] ?: lowercaseEntries[path.lowercase()]

    fun has(path: String) = entry(path) != null

    /** Opens part [path], failing once more than [maxBytes] have been read from it. */
    fun open(path: String, maxBytes: Long = MAX_PART_BYTES): InputStream {
        val entry = entry(path) ?: throw OfficeFileException("Part of this file is missing.")
        return LimitedInputStream(zip.getInputStream(entry), maxBytes)
    }

    fun bytes(path: String, maxBytes: Long = MAX_PART_BYTES): ByteArray = open(path, maxBytes).use { it.readBytes() }

    /** Streams part [path] through [handler]. */
    fun sax(path: String, handler: DefaultHandler, maxBytes: Long = MAX_PART_BYTES) {
        open(path, maxBytes).use { input -> parseXml(input, handler) }
    }

    /** Reads a small part [path] into a tree. */
    fun xml(path: String): XmlNode = open(path, MAX_TREE_BYTES).use(::readXmlTree)

    /** The relationships of part [path] ("" for the package itself), with targets resolved to part paths. */
    fun relationships(path: String): List<Relationship> {
        val relsPath = relsPathOf(path)
        if (!has(relsPath)) return emptyList()
        return xml(relsPath).children("Relationship").mapNotNull { node ->
            val id = node.attr("Id") ?: return@mapNotNull null
            val target = node.attr("Target") ?: return@mapNotNull null
            val external = node.attr("TargetMode") == "External"
            Relationship(id, node.attr("Type").orEmpty(), if (external) target else resolvePart(path, target), external)
        }
    }

    /** Relationships of [path] by id. */
    fun relationshipMap(path: String): Map<String, Relationship> = relationships(path).associateBy { it.id }

    /** The workbook or presentation part, from the package's own relationships. */
    fun mainPart(): String? = relationships("").firstOrNull { it.isType("officeDocument") && !it.external }?.target

    override fun close() = zip.close()

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_PART_BYTES = 256L * 1024 * 1024
        const val MAX_TREE_BYTES = 16L * 1024 * 1024
        const val MAX_TREE_NODES = 250_000
        const val NOT_OFFICE = "This isn't an Excel or PowerPoint file Books can open."

        fun relsPathOf(path: String): String {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            return if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        }

        /** Resolves a relationship [target] written in part [source] to a part path inside the package. */
        fun resolvePart(source: String, target: String): String {
            val clean = percentDecode(target.substringBefore('#'))
            val joined = if (clean.startsWith("/")) clean else source.substringBeforeLast('/', "") + "/" + clean
            val parts = ArrayDeque<String>()
            joined.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(segment)
                }
            }
            return parts.joinToString("/")
        }

        /** Decodes %XX escapes (as UTF-8) in a link target; anything else is kept as written. */
        internal fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            val pending = java.io.ByteArrayOutputStream()
            fun flush() {
                if (pending.size() > 0) out.append(pending.toString("UTF-8"))
                pending.reset()
            }
            var i = 0
            while (i < value.length) {
                val hex = if (value[i] == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    pending.write(hex)
                    i += 3
                } else {
                    flush()
                    out.append(value[i])
                    i++
                }
            }
            flush()
            return out.toString()
        }
    }
}

/** Fails once more than [limit] bytes have been read, so a tiny compressed part can't inflate without bound. */
private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }

    private fun add(n: Long) {
        count += n
        if (count > limit) throw OfficeFileException("Part of this file is too large to open.")
    }
}

/**
 * Refuses XML with a document type declaration. Office files never have one, and it's the only way
 * in for entity tricks ("billion laughs", external entities), so this guards every parser on every
 * Android version regardless of what its own settings support. Checks UTF-8 and both UTF-16 orders.
 */
private class DoctypeGuard(input: InputStream) : FilterInputStream(input) {
    private val patterns = DOCTYPE_PATTERNS
    private val matched = IntArray(patterns.size)

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) scan(b.toByte())
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in off until off + n.coerceAtLeast(0)) scan(b[i])
        return n
    }

    private fun scan(byte: Byte) {
        for (k in patterns.indices) {
            val pattern = patterns[k]
            var m = matched[k]
            // Knuth-Morris-Pratt step: fall back along the pattern's own prefixes on a mismatch.
            while (m > 0 && pattern.bytes[m] != byte) m = pattern.failure[m - 1]
            if (pattern.bytes[m] == byte) m++
            if (m == pattern.bytes.size) throw OfficeFileException("This file contains something Books won't open.")
            matched[k] = m
        }
    }

    private class Pattern(val bytes: ByteArray) {
        val failure = IntArray(bytes.size).also { f ->
            var k = 0
            for (i in 1 until bytes.size) {
                while (k > 0 && bytes[i] != bytes[k]) k = f[k - 1]
                if (bytes[i] == bytes[k]) k++
                f[i] = k
            }
        }
    }

    private companion object {
        val DOCTYPE_PATTERNS = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).map { Pattern("<!DOCTYPE".toByteArray(it)) }
    }
}

/** Parses XML from [input] into [handler] with DTDs and external entities refused. */
fun parseXml(input: InputStream, handler: DefaultHandler) {
    val reader = secureReader()
    reader.contentHandler = handler
    reader.errorHandler = handler
    try {
        reader.parse(InputSource(DoctypeGuard(input)))
    } catch (e: StopParsing) {
        throw e
    } catch (e: OfficeFileException) {
        throw e
    } catch (e: SAXException) {
        // Exceptions from the guard or a handler can arrive wrapped by the parser.
        (e.exception as? StopParsing)?.let { throw it }
        (e.exception as? OfficeFileException)?.let { throw it }
        throw OfficeFileException(DAMAGED)
    } catch (e: IOException) {
        throw OfficeFileException(DAMAGED)
    }
}

internal const val DAMAGED = "This file is damaged and can't be opened."

/** Thrown from a handler to stop reading early (a sheet reached its size cap); not an error. */
class StopParsing : SAXException("stop")

private fun secureReader(): XMLReader {
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
    }
    // Not every parser knows every setting; the doctype guard above covers the ones it doesn't.
    listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
    return factory.newSAXParser().xmlReader.apply {
        entityResolver = EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
    }
}

/** A parsed XML element. Names are local names; namespaces are ignored except for relationship ids. */
class XmlNode(val name: String, private val attributes: Map<String, String>) {
    private val _children = ArrayList<XmlNode>(0)
    val children: List<XmlNode> get() = _children
    private var builder: StringBuilder? = null

    /** The element's own text (not its children's). */
    val text: String get() = builder?.toString().orEmpty()

    fun attr(name: String): String? = attributes[name]

    /** An `r:id`, `r:embed` or `r:link` attribute. */
    fun relAttr(name: String): String? = attributes[REL_PREFIX + name]

    fun child(name: String): XmlNode? = _children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlNode> = _children.filter { it.name == name }

    /** The first descendant reached by following [names] in order, one child level at a time. */
    fun path(vararg names: String): XmlNode? {
        var node: XmlNode? = this
        for (n in names) node = node?.child(n) ?: return null
        return node
    }

    internal fun add(child: XmlNode) {
        _children.add(child)
    }

    internal fun append(chars: CharArray, start: Int, length: Int) {
        (builder ?: StringBuilder().also { builder = it }).appendRange(chars, start, start + length)
    }

    companion object {
        internal const val REL_PREFIX = "r:"
        private val RELATIONSHIP_NAMESPACES = setOf(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "http://purl.oclc.org/ooxml/officeDocument/relationships",
        )

        internal fun attributesOf(attrs: Attributes): Map<String, String> {
            if (attrs.length == 0) return emptyMap()
            val map = HashMap<String, String>(attrs.length * 2)
            for (i in 0 until attrs.length) {
                val uri = attrs.getURI(i).orEmpty()
                val local = attrs.getLocalName(i)?.takeIf { it.isNotEmpty() } ?: attrs.getQName(i).substringAfter(':')
                when {
                    uri.isEmpty() -> map[local] = attrs.getValue(i)
                    uri in RELATIONSHIP_NAMESPACES -> map[REL_PREFIX + local] = attrs.getValue(i)
                }
            }
            return map
        }
    }
}

private fun readXmlTree(input: InputStream): XmlNode {
    val handler = object : DefaultHandler() {
        val stack = ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        var nodes = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            if (++nodes > OfficePackage.MAX_TREE_NODES) throw OfficeFileException("This file is too complex to open.")
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            val node = XmlNode(name, XmlNode.attributesOf(attributes))
            stack.lastOrNull()?.add(node) ?: run { root = node }
            stack.addLast(node)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            stack.removeLastOrNull()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.append(ch, start, length)
        }
    }
    parseXml(input, handler)
    return handler.root ?: throw OfficeFileException(DAMAGED)
}
