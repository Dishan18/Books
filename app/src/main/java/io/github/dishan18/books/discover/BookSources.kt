package io.github.dishan18.books.discover

import io.github.dishan18.books.discover.catalog.BookCatalog
import io.github.dishan18.books.discover.catalog.GutenbergCatalog
import io.github.dishan18.books.discover.catalog.OnlineBook
import io.github.dishan18.books.discover.catalog.OpdsCatalog
import io.github.dishan18.books.discover.catalog.fetchFeed
import io.github.dishan18.books.discover.data.BookSourceDao
import io.github.dishan18.books.discover.data.BookSourceEntity
import io.github.dishan18.books.discover.data.BookSourceType
import io.github.dishan18.books.manga.source.CredentialStore
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.net.HostAuthInterceptor
import io.github.dishan18.books.manga.source.net.MangaNetwork
import io.github.dishan18.books.manga.source.net.RateLimitInterceptor
import io.github.dishan18.books.manga.source.net.RateLimiter
import kotlinx.coroutines.flow.Flow
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The book catalogs: Project Gutenberg, plus OPDS catalogs the user adds. */
class BookSources(
    private val dao: BookSourceDao,
    private val network: MangaNetwork,
    private val credentials: CredentialStore,
) {
    private data class Built(val config: BookSourceEntity, val catalog: BookCatalog, val client: OkHttpClient, val headers: Map<String, String>)

    private val built = ConcurrentHashMap<String, Built>()
    private val books = ConcurrentHashMap<String, OnlineBook>()

    // Polite to Project Gutenberg's volunteers: at most two catalog requests a second.
    private val gutenbergLimiter = RateLimiter(permits = 2, periodMillis = 1_000)

    val all: Flow<List<BookSourceEntity>> = dao.observeAll()

    suspend fun ensureDefaults() {
        dao.insertIfMissing(
            BookSourceEntity(
                id = GutenbergCatalog.DEFAULT_ID,
                type = BookSourceType.GUTENBERG.name,
                name = "Project Gutenberg",
                url = "https://www.gutenberg.org",
                sortOrder = 0,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun get(id: String): BookSourceEntity? = dao.get(id)

    suspend fun catalog(id: String): BookCatalog? = build(id)?.catalog

    /** The HTTP client for [id], with that catalog's sign-in applied to its own host only. */
    suspend fun client(id: String): OkHttpClient? = build(id)?.client

    private suspend fun build(id: String): Built? {
        // A download can start before the first-run defaults are written.
        if (id == GutenbergCatalog.DEFAULT_ID) ensureDefaults()
        val config = dao.get(id) ?: return null
        built[id]?.takeIf { it.config == config }?.let { return it }
        val result = when (BookSourceType.valueOf(config.type)) {
            BookSourceType.GUTENBERG -> {
                val client = network.clientFor(
                    RateLimitInterceptor(listOf({ request: okhttp3.Request -> request.url.host.endsWith("gutenberg.org") } to gutenbergLimiter)),
                )
                Built(config, GutenbergCatalog(config.id, client), client, emptyMap())
            }
            BookSourceType.OPDS -> {
                val headers = authHeaders(config.url, config.username, credentials.get(secretKey(config.id)))
                val client = opdsClient(config.url, headers)
                Built(config, OpdsCatalog(config.id, config.name, config.url, client), client, headers)
            }
        }
        return result.also { built[id] = it }
    }

    /** Headers for loading a cover image at [url]: the catalog's sign-in, and only for the catalog's own host. */
    fun imageHeaders(id: String, url: String?): Map<String, String> {
        val entry = built[id] ?: return emptyMap()
        val host = url?.toHttpUrlOrNull()?.host ?: return emptyMap()
        return if (host.equals(entry.config.url.toHttpUrlOrNull()?.host, ignoreCase = true)) entry.headers else emptyMap()
    }

    private fun authHeaders(url: String, username: String?, password: String?): Map<String, String> =
        if (url.toHttpUrlOrNull() != null && !username.isNullOrBlank() && password != null) {
            mapOf("Authorization" to Credentials.basic(username, password))
        } else {
            emptyMap()
        }

    private fun opdsClient(url: String, headers: Map<String, String>): OkHttpClient =
        network.clientFor(HostAuthInterceptor(url.toHttpUrlOrNull()?.host.orEmpty(), headers))

    /** Checks that [url] answers as an OPDS catalog with these details. */
    suspend fun test(url: String, username: String?, password: String?): Result<String?> = runSource {
        opdsClient(url, authHeaders(url, username, password)).fetchFeed(url).title
    }

    /** Adds or updates an OPDS catalog. A null [password] when editing keeps the saved one. */
    suspend fun saveOpds(id: String?, name: String, url: String, username: String?, password: String?): String {
        val sourceId = id ?: "opds-${UUID.randomUUID()}"
        val existing = dao.get(sourceId)
        if (password != null || existing == null || username.isNullOrBlank()) {
            credentials.set(secretKey(sourceId), password.takeUnless { username.isNullOrBlank() })
        }
        built.remove(sourceId)
        dao.upsert(
            BookSourceEntity(
                id = sourceId,
                type = BookSourceType.OPDS.name,
                name = name.trim().ifBlank { "My catalog" },
                url = url.trim(),
                username = username?.trim()?.ifBlank { null },
                sortOrder = existing?.sortOrder ?: 10,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            ),
        )
        return sourceId
    }

    suspend fun remove(id: String) {
        val config = dao.get(id) ?: return
        require(config.type == BookSourceType.OPDS.name) { "Project Gutenberg can't be removed" }
        credentials.set(secretKey(id), null)
        built.remove(id)
        dao.delete(id)
    }

    fun hasPassword(id: String) = credentials.get(secretKey(id)) != null

    fun savedPassword(id: String) = credentials.get(secretKey(id))

    // Books seen in listings, so the details screen opens instantly with what's already known.

    fun remember(book: OnlineBook) {
        books[bookKey(book.sourceId, book.key)] = book
    }

    fun remembered(sourceId: String, key: String): OnlineBook? = books[bookKey(sourceId, key)]

    private fun bookKey(sourceId: String, key: String) = "$sourceId\u0000$key"

    private fun secretKey(id: String) = "book-source:$id"
}
