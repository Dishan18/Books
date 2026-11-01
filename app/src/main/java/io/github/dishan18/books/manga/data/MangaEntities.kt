package io.github.dishan18.books.manga.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A configured content source: MangaDex, one of the user's Komga servers, or local imports. */
@Entity(tableName = "manga_sources")
data class SourceConfigEntity(
    @PrimaryKey val id: String,
    /** [io.github.dishan18.books.manga.source.SourceType] name. */
    val type: String,
    val name: String,
    val enabled: Boolean,
    /** Type-specific settings as JSON (never contains secrets; see CredentialStore). */
    val settings: String,
    val sortOrder: Int,
    val addedAt: Long,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
)

enum class SeriesStatus { UNKNOWN, ONGOING, COMPLETED, HIATUS, CANCELLED }

@Entity(
    tableName = "manga_series",
    indices = [
        Index(value = ["sourceId", "sourceKey"], unique = true),
        Index("favorite"),
    ],
)
data class MangaSeries(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    /** The series' identifier inside its source. */
    val sourceKey: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val status: SeriesStatus = SeriesStatus.UNKNOWN,
    /** Genres separated by newlines. */
    val genres: String = "",
    val webUrl: String? = null,
    val favorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastReadAt: Long? = null,
    val detailsUpdatedAt: Long? = null,
    val chaptersUpdatedAt: Long? = null,
    val createdAt: Long,
) {
    val genreList: List<String> get() = genres.split('\n').filter { it.isNotBlank() }
}

@Entity(
    tableName = "manga_chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaSeries::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seriesId", "sourceKey"], unique = true)],
)
data class MangaChapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    /** The chapter's identifier inside its source. */
    val sourceKey: String,
    val name: String,
    val number: Float? = null,
    val volume: String? = null,
    val scanlator: String? = null,
    val uploadedAt: Long? = null,
    /** Position in reading order, 0 = first chapter. */
    val sortIndex: Int,
    val read: Boolean = false,
    /** Page the reader was last on, and how far through that page (0..1). */
    val lastPageRead: Int = 0,
    val pageOffset: Float = 0f,
    val pageCount: Int? = null,
    val readAt: Long? = null,
    /** When this chapter first appeared in the library, for "new" markers. */
    val addedAt: Long,
)

/** One row per chapter the user opened; the latest per series forms the history list. */
@Entity(
    tableName = "manga_history",
    foreignKeys = [
        ForeignKey(
            entity = MangaChapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MangaSeries::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("seriesId"), Index("readAt")],
)
data class MangaHistory(
    @PrimaryKey val chapterId: Long,
    val seriesId: Long,
    val readAt: Long,
)

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

@Entity(
    tableName = "manga_downloads",
    foreignKeys = [
        ForeignKey(
            entity = MangaChapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MangaSeries::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("seriesId"), Index("state")],
)
data class MangaDownload(
    @PrimaryKey val chapterId: Long,
    val seriesId: Long,
    val state: DownloadState,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val error: String? = null,
    val attempts: Int = 0,
    val queuedAt: Long,
    val updatedAt: Long,
)
