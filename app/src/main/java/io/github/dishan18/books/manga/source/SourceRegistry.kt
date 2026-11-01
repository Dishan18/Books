package io.github.dishan18.books.manga.source

import io.github.dishan18.books.manga.data.SourceConfigDao
import io.github.dishan18.books.manga.data.SourceConfigEntity
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.komga.KomgaSource
import io.github.dishan18.books.manga.source.local.LocalSource
import io.github.dishan18.books.manga.source.mangadex.MangaDexSource
import io.github.dishan18.books.manga.source.net.HostAuthInterceptor
import io.github.dishan18.books.manga.source.net.MangaNetwork
import io.github.dishan18.books.manga.source.net.SourceJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns saved source configs into live [MangaSource] instances. A source that fails to build
 * (bad settings, missing credentials) is reported on its own entry and never affects the others.
 *
 * Disabling a source hides it from Browse and search; series already in the library keep working.
 */
class SourceRegistry(
    private val dao: SourceConfigDao,
    private val network: MangaNetwork,
    private val credentials: CredentialStore,
    val local: LocalSource,
    scope: CoroutineScope,
) {
    data class Entry(val config: SourceConfigEntity, val source: MangaSource?, val loadError: String?) {
        val id get() = config.id
        val type get() = runCatching { SourceType.valueOf(config.type) }.getOrNull()
        val usable get() = source != null
    }

    private data class Built(val fingerprint: String, val result: Result<MangaSource>)

    private val built = ConcurrentHashMap<String, Built>()

    val entries: StateFlow<List<Entry>> = dao.observeAll()
        .map { configs -> configs.map { entryFor(it) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Creates MangaDex and the local source on first launch. */
    suspend fun ensureDefaults() {
        val now = System.currentTimeMillis()
        dao.insertIfMissing(
            SourceConfigEntity(
                id = MangaDexSource.ID,
                type = SourceType.MANGADEX.name,
                name = "MangaDex",
                enabled = true,
                settings = SourceJson.encodeToString(MangaDexSettings.serializer(), MangaDexSettings(languages = defaultLanguages())),
                sortOrder = 0,
                addedAt = now,
            ),
        )
        dao.insertIfMissing(
            SourceConfigEntity(
                id = LocalSource.ID,
                type = SourceType.LOCAL.name,
                name = "On this device",
                enabled = true,
                settings = "{}",
                sortOrder = 100,
                addedAt = now,
            ),
        )
    }

    /** The source for [id], built on demand. Throws [SourceException] if it was removed or is misconfigured. */
    suspend fun require(id: String): MangaSource {
        entries.value.firstOrNull { it.id == id }?.source?.let { return it }
        val config = dao.get(id) ?: throw SourceException(SourceError.Other("This source was removed"))
        val entry = entryFor(config)
        return entry.source ?: throw SourceException(SourceError.Other(entry.loadError ?: "Source unavailable"))
    }

    fun peek(id: String): MangaSource? = entries.value.firstOrNull { it.id == id }?.source

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun updateMangaDex(settings: MangaDexSettings) {
        val config = dao.get(MangaDexSource.ID) ?: return
        dao.upsert(config.copy(settings = SourceJson.encodeToString(MangaDexSettings.serializer(), settings)))
    }

    /** Checks that a Komga server answers with these details before saving it. */
    suspend fun testKomga(settings: KomgaSettings, secret: String?): Result<Unit> = runSource {
        val probe = buildKomga("komga-probe", "Probe", settings, secret)
        probe.browse(Listing.POPULAR, 1)
        Unit
    }

    suspend fun saveKomga(id: String?, name: String, settings: KomgaSettings, secret: String?): String {
        val sourceId = id ?: "komga-${UUID.randomUUID()}"
        val existing = dao.get(sourceId)
        // A null secret on edit means "keep the current one".
        if (secret != null || existing == null) credentials.set(sourceId, secret)
        built.remove(sourceId)
        dao.upsert(
            SourceConfigEntity(
                id = sourceId,
                type = SourceType.KOMGA.name,
                name = name.trim().ifBlank { "Komga" },
                enabled = existing?.enabled ?: true,
                settings = SourceJson.encodeToString(KomgaSettings.serializer(), settings.normalized()),
                sortOrder = existing?.sortOrder ?: 10,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            ),
        )
        return sourceId
    }

    suspend fun remove(id: String) {
        val config = dao.get(id) ?: return
        require(config.type == SourceType.KOMGA.name) { "Built-in sources can't be removed" }
        credentials.set(id, null)
        built.remove(id)
        dao.delete(id)
    }

    suspend fun recordResult(id: String, error: SourceError?) {
        if (error == SourceError.Offline) return // Not the source's fault.
        dao.setHealth(id, error?.message, if (error == null) null else System.currentTimeMillis())
    }

    fun mangaDexSettings(config: SourceConfigEntity): MangaDexSettings =
        runCatching { SourceJson.decodeFromString(MangaDexSettings.serializer(), config.settings) }.getOrDefault(MangaDexSettings())

    fun komgaSettings(config: SourceConfigEntity): KomgaSettings? =
        runCatching { SourceJson.decodeFromString(KomgaSettings.serializer(), config.settings) }.getOrNull()

    fun hasSecret(id: String) = credentials.get(id) != null

    private fun entryFor(config: SourceConfigEntity): Entry {
        val fingerprint = config.type + '\u0000' + config.name + '\u0000' + config.settings
        val cached = built[config.id]?.takeIf { it.fingerprint == fingerprint }
        val result = cached?.result ?: runCatching { create(config) }.also { built[config.id] = Built(fingerprint, it) }
        return Entry(config, result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: "Couldn't load this source" })
    }

    private fun create(config: SourceConfigEntity): MangaSource = when (SourceType.valueOf(config.type)) {
        SourceType.MANGADEX -> MangaDexSource(network.clientFor(MangaDexSource.rateLimits()), mangaDexSettings(config))
        SourceType.LOCAL -> local
        SourceType.KOMGA -> {
            val settings = komgaSettings(config) ?: error("Server settings are invalid")
            buildKomga(config.id, config.name, settings, credentials.get(config.id))
        }
    }

    private fun buildKomga(id: String, name: String, settings: KomgaSettings, secret: String?): KomgaSource {
        val url = settings.baseUrl.toHttpUrlOrNull() ?: error("Server address is invalid")
        val headers = when (settings.auth) {
            KomgaAuth.NONE -> emptyMap()
            KomgaAuth.BASIC -> {
                if (settings.username.isNullOrBlank() || secret == null) error("Enter the username and password again")
                mapOf("Authorization" to Credentials.basic(settings.username, secret))
            }
            KomgaAuth.API_KEY -> mapOf("X-API-Key" to (secret ?: error("Enter the API key again")))
        }
        val client = network.clientFor(HostAuthInterceptor(url.host, headers))
        return KomgaSource(id, name, client, settings.baseUrl, headers)
    }

    private fun KomgaSettings.normalized() = copy(baseUrl = baseUrl.trim().trimEnd('/'), username = username?.trim())

    private fun defaultLanguages(): List<String> {
        val device = Locale.getDefault()
        val code = when (device.language) {
            "pt" -> if (device.country == "BR") "pt-br" else "pt"
            "es" -> if (device.country in setOf("ES", "")) "es" else "es-la"
            "zh" -> if (device.country in setOf("TW", "HK")) "zh-hk" else "zh"
            else -> device.language
        }
        return listOf("en", code).distinct().filter { it.isNotBlank() }
    }
}
