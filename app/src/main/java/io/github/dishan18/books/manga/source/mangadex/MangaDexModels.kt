package io.github.dishan18.books.manga.source.mangadex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Subset of the MangaDex API v5 response models (https://api.mangadex.org/docs/).

@Serializable
data class MdListResponse<T>(
    val result: String,
    val data: List<T> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
data class MdEntityResponse<T>(val result: String, val data: T)

@Serializable
data class MdManga(
    val id: String,
    val attributes: MdMangaAttributes,
    val relationships: List<MdRelationship> = emptyList(),
)

@Serializable
data class MdMangaAttributes(
    /** Localized strings arrive as `{"en": "…"}`; MangaDex sends `[]` for empty ones. */
    val title: JsonElement? = null,
    val altTitles: List<JsonElement> = emptyList(),
    val description: JsonElement? = null,
    val status: String? = null,
    val tags: List<MdTag> = emptyList(),
    val originalLanguage: String? = null,
)

@Serializable
data class MdTag(val id: String, val attributes: MdTagAttributes)

@Serializable
data class MdTagAttributes(val name: JsonElement? = null, val group: String? = null)

@Serializable
data class MdRelationship(
    val id: String,
    val type: String,
    val attributes: JsonObject? = null,
)

@Serializable
data class MdChapter(
    val id: String,
    val attributes: MdChapterAttributes,
    val relationships: List<MdRelationship> = emptyList(),
)

@Serializable
data class MdChapterAttributes(
    val volume: String? = null,
    val chapter: String? = null,
    val title: String? = null,
    val translatedLanguage: String? = null,
    val externalUrl: String? = null,
    val isUnavailable: Boolean = false,
    val publishAt: String? = null,
    val readableAt: String? = null,
    val pages: Int = 0,
)

@Serializable
data class MdAtHome(val result: String, val baseUrl: String, val chapter: MdAtHomeChapter)

@Serializable
data class MdAtHomeChapter(
    val hash: String,
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)

@Serializable
data class MdAtHomeReport(
    val url: String,
    val success: Boolean,
    val cached: Boolean,
    val bytes: Long,
    val duration: Long,
)

/** Picks the best string from a MangaDex localized-string object for [languages]. */
internal fun JsonElement?.localized(languages: List<String>): String? {
    val map = this as? JsonObject ?: return null
    fun value(lang: String) = (map[lang] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return languages.firstNotNullOfOrNull { value(it) }
        ?: value("en")
        ?: map.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
