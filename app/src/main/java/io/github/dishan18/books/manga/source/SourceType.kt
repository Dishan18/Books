package io.github.dishan18.books.manga.source

import kotlinx.serialization.Serializable

enum class SourceType(val label: String) {
    MANGADEX("MangaDex"),
    KOMGA("Komga server"),
    LOCAL("On this device"),
}

/** MangaDex preferences. Content ratings use MangaDex's own values. */
@Serializable
data class MangaDexSettings(
    val languages: List<String> = listOf("en"),
    val contentRatings: List<String> = listOf("safe", "suggestive"),
    val dataSaver: Boolean = false,
) {
    companion object {
        val ALL_CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
    }
}

enum class KomgaAuth { NONE, BASIC, API_KEY }

/** A Komga server. The password or API key lives in [CredentialStore], never here. */
@Serializable
data class KomgaSettings(
    val baseUrl: String,
    val auth: KomgaAuth = KomgaAuth.NONE,
    val username: String? = null,
)
