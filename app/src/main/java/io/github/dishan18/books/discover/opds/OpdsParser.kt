package io.github.dishan18.books.discover.opds

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

data class OpdsLink(
    val href: String,
    val rel: String?,
    val type: String?,
    val title: String?,
    /** Size in bytes, when the catalog says. */
    val length: Long?,
) {
    val isAcquisition get() = rel?.startsWith(ACQUISITION) == true
    /** Free to download: plain or open-access acquisition, not buy, borrow, subscribe or sample. */
    val isFreeAcquisition get() = rel == ACQUISITION || rel == "$ACQUISITION/open-access"
    val isImage get() = rel == "http://opds-spec.org/image" || rel == "http://opds-spec.org/cover" || rel == "x-stanza-cover-image"
    val isThumbnail get() = rel == "http://opds-spec.org/image/thumbnail" || rel == "http://opds-spec.org/thumbnail" || rel == "x-stanza-cover-image-thumbnail"
    val isCatalog get() = type?.contains("application/atom+xml") == true && !type.contains("type=entry")
    val isFullEntry get() = type?.contains("application/atom+xml") == true && type.contains("type=entry")

    private companion object {
        const val ACQUISITION = "http://opds-spec.org/acquisition"
    }
}

data class OpdsEntry(
    val id: String?,
    val title: String,
    val authors: List<String>,
    /** Plain text from `summary`, or from `content` when there's no summary. */
    val summary: String?,
    /** `content` split into paragraphs (some catalogs put labelled details there). */
    val contentParagraphs: List<String>,
    /** Category labels (or terms) with their scheme. */
    val categories: List<Pair<String, String?>>,
    val language: String?,
    val rights: String?,
    val links: List<OpdsLink>,
) {
    val acquisitions get() = links.filter { it.isAcquisition }
    val coverUrl get() = (links.firstOrNull { it.isImage } ?: links.firstOrNull { it.isThumbnail })?.href?.takeUnless { it.startsWith("data:") }
    val thumbnailUrl get() = (links.firstOrNull { it.isThumbnail } ?: links.firstOrNull { it.isImage })?.href?.takeUnless { it.startsWith("data:") }
    /** Where browsing into this entry leads, for navigation entries. */
    val navigationLink get() = links.firstOrNull { it.isCatalog && it.rel !in NOT_NAVIGATION }

    private companion object {
        val NOT_NAVIGATION = setOf("related", "self", "up", "start", "search", "next", "previous")
    }
}

data class OpdsFeed(
    val title: String?,
    val entries: List<OpdsEntry>,
    val nextUrl: String?,
    /** A search link: an OpenSearch description, or a template containing `{searchTerms}`. */
    val search: OpdsLink?,
)

/**
 * Parses OPDS 1.x catalogs (Atom). Relative links are resolved against the feed's address.
 * Uses the platform DOM parser with DTDs and external entities refused, so a hostile catalog
 * can't make the parser fetch or expand anything.
 */
object OpdsParser {

    class NotAFeedException(message: String) : Exception(message)

    fun parse(input: InputStream, baseUrl: String): OpdsFeed = parse(InputSource(input), baseUrl)

    fun parse(xml: String, baseUrl: String): OpdsFeed = parse(InputSource(StringReader(xml)), baseUrl)

    private fun parse(source: InputSource, baseUrl: String): OpdsFeed {
        val root = newBuilder().parse(source).documentElement
        if (root.localName != "feed" && root.localName != "entry") throw NotAFeedException("Not an OPDS catalog")
        val base = baseUrl
        if (root.localName == "entry") return OpdsFeed(null, listOf(parseEntry(root, base)), null, null)
        val links = children(root, "link").map { parseLink(it, base) }
        return OpdsFeed(
            title = child(root, "title")?.textContent?.clean(),
            entries = children(root, "entry").map { parseEntry(it, base) },
            nextUrl = links.firstOrNull { it.rel == "next" }?.href,
            search = links.firstOrNull { it.rel == "search" },
        )
    }

    /** Reads the Atom search template from an OpenSearch description document. */
    fun parseOpenSearchTemplate(xml: String, baseUrl: String): String? {
        val root = newBuilder().parse(InputSource(StringReader(xml))).documentElement
        val urls = children(root, "Url")
        val url = urls.firstOrNull { it.getAttribute("type").contains("atom") } ?: urls.firstOrNull() ?: return null
        return url.getAttribute("template").takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) }
    }

    /** Fills an OpenSearch template: the query goes in `{searchTerms}`, optional parameters are dropped. */
    fun fillTemplate(template: String, query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return template.replace("{searchTerms}", encoded)
            .replace(Regex("\\{[^}]*\\?}"), "")
            .replace(Regex("\\{startPage}"), "1")
            .replace(Regex("\\{startIndex}"), "1")
            .replace(Regex("\\{count}"), "")
    }

    private fun newBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // Not every platform parser implements these switches; the entity resolver below covers the rest.
        runCatching { isXIncludeAware = false }
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
    }.newDocumentBuilder().apply {
        // Never resolve DTDs or entities from the network or disk.
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }

    private fun parseEntry(entry: Element, base: String): OpdsEntry {
        val content = child(entry, "content")
        val paragraphs = content?.let(::paragraphsOf).orEmpty()
        val summary = child(entry, "summary")?.let { textOf(it) }?.takeIf { it.isNotBlank() }
            ?: paragraphs.joinToString("\n\n").takeIf { it.isNotBlank() }
        return OpdsEntry(
            id = child(entry, "id")?.textContent?.trim(),
            title = child(entry, "title")?.textContent?.clean().orEmpty().ifBlank { "Untitled" },
            authors = children(entry, "author").mapNotNull { child(it, "name")?.textContent?.clean() }.filter { it.isNotBlank() },
            summary = summary,
            contentParagraphs = paragraphs,
            categories = children(entry, "category").mapNotNull { category ->
                val label = category.getAttribute("label").ifBlank { category.getAttribute("term") }.clean()
                label.takeIf { it.isNotBlank() }?.let { it to category.getAttribute("scheme").ifBlank { null } }
            },
            language = children(entry, "language").firstOrNull()?.textContent?.trim()?.ifBlank { null },
            rights = child(entry, "rights")?.textContent?.clean()?.ifBlank { null },
            links = children(entry, "link").map { parseLink(it, base) },
        )
    }

    private fun parseLink(link: Element, base: String) = OpdsLink(
        href = resolve(base, link.getAttribute("href")),
        rel = link.getAttribute("rel").ifBlank { null },
        type = link.getAttribute("type").ifBlank { null },
        title = link.getAttribute("title").ifBlank { null },
        length = link.getAttribute("length").toLongOrNull(),
    )

    /**
     * Resolves [href] against [base] per RFC 3986 (OkHttp's resolver; java.net.URI gets query-only
     * links like "?page=2" wrong). Templates keep their `{placeholders}` unencoded.
     */
    private fun resolve(base: String, href: String): String {
        val value = href.trim()
        if (value.startsWith("data:") || value.contains("://")) return value
        val baseUrl = base.toHttpUrlOrNull() ?: return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
        if (!value.contains('{')) return baseUrl.resolve(value)?.toString() ?: value
        val origin = "${baseUrl.scheme}://${baseUrl.host}" + if (baseUrl.port != HttpUrl.defaultPort(baseUrl.scheme)) ":${baseUrl.port}" else ""
        return when {
            value.startsWith("//") -> "${baseUrl.scheme}:$value"
            value.startsWith("/") -> origin + value
            else -> origin + baseUrl.encodedPath.substringBeforeLast('/') + "/" + value
        }
    }

    /** Text of `content`/`summary`: XHTML children as text, escaped HTML with its tags stripped. */
    private fun textOf(element: Element): String = paragraphsOf(element).joinToString("\n\n")

    private fun paragraphsOf(element: Element): List<String> {
        val type = element.getAttribute("type")
        return when {
            type == "xhtml" -> {
                val blocks = mutableListOf<String>()
                collectBlocks(element, blocks)
                blocks.map { it.clean() }.filter { it.isNotBlank() }
            }
            type == "html" || type == "text/html" -> htmlToParagraphs(element.textContent)
            else -> element.textContent.split(Regex("\n\\s*\n")).map { it.clean() }.filter { it.isNotBlank() }
        }
    }

    private fun collectBlocks(node: Node, out: MutableList<String>) {
        val children = node.childNodes
        val inline = StringBuilder()
        for (i in 0 until children.length) {
            val child = children.item(i)
            val name = (child as? Element)?.localName
            if (name in BLOCKS) {
                if (inline.isNotBlank()) out += inline.toString().also { inline.clear() }
                if ((child as Element).getElementsByTagNameNS("*", "p").length > 0 && name == "div") collectBlocks(child, out) else out += child.textContent
            } else {
                inline.append(child.textContent)
            }
        }
        if (inline.isNotBlank()) out += inline.toString()
    }

    private fun htmlToParagraphs(html: String): List<String> =
        html.replace(Regex("(?i)<br\\s*/?>"), "\n").split(Regex("(?i)</p>|<p[^>]*>|\n\\s*\n"))
            .map { unescape(it.replace(Regex("<[^>]+>"), "")).clean() }
            .filter { it.isNotBlank() }

    private fun unescape(text: String) = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

    private fun String.clean() = replace(Regex("\\s+"), " ").trim()

    private fun child(parent: Element, name: String): Element? = children(parent, name).firstOrNull()

    private fun children(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { (it.localName ?: it.nodeName) == name }
    }

    private val BLOCKS = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "blockquote")
}
