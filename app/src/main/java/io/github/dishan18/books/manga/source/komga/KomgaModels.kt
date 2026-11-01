package io.github.dishan18.books.manga.source.komga

import kotlinx.serialization.Serializable

// Subset of the Komga REST API v1 models (https://komga.org/docs/openapi/komga-api).

@Serializable
data class KomgaPage<T>(
    val content: List<T> = emptyList(),
    val number: Int = 0,
    val totalPages: Int = 0,
    val last: Boolean = true,
)

@Serializable
data class KomgaSeries(
    val id: String,
    val name: String,
    val metadata: KomgaSeriesMetadata = KomgaSeriesMetadata(),
    val booksMetadata: KomgaBooksMetadata = KomgaBooksMetadata(),
)

@Serializable
data class KomgaSeriesMetadata(
    val title: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class KomgaBooksMetadata(
    val authors: List<KomgaAuthor> = emptyList(),
    val summary: String? = null,
)

@Serializable
data class KomgaAuthor(val name: String, val role: String = "")

@Serializable
data class KomgaBook(
    val id: String,
    val name: String,
    val number: Float? = null,
    val created: String? = null,
    val metadata: KomgaBookMetadata = KomgaBookMetadata(),
    val media: KomgaMedia = KomgaMedia(),
)

@Serializable
data class KomgaBookMetadata(
    val title: String? = null,
    val number: String? = null,
    val numberSort: Float? = null,
    val releaseDate: String? = null,
)

@Serializable
data class KomgaMedia(val pagesCount: Int = 0, val status: String? = null)

@Serializable
data class KomgaBookPage(val number: Int, val fileName: String? = null, val mediaType: String? = null)
