package io.github.dishan18.books.manga.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A favorite series with the counts the library grid shows. */
data class LibrarySeries(
    @Embedded val series: MangaSeries,
    val unreadCount: Int,
    val chapterCount: Int,
    val downloadedCount: Int,
)

/** The latest chapter read in a series. */
data class HistoryEntry(
    val seriesId: Long,
    val seriesTitle: String,
    val coverUrl: String?,
    val sourceId: String,
    val chapterId: Long,
    val chapterName: String,
    val chapterRead: Boolean,
    val lastPageRead: Int,
    val pageCount: Int?,
    val readAt: Long,
)

/** A download row joined with what the downloads screen displays. */
data class DownloadItem(
    @Embedded val download: MangaDownload,
    val chapterName: String,
    val seriesTitle: String,
    val sourceId: String,
)

@Dao
interface SourceConfigDao {
    @Query("SELECT * FROM manga_sources ORDER BY sortOrder, addedAt")
    fun observeAll(): Flow<List<SourceConfigEntity>>

    @Query("SELECT * FROM manga_sources ORDER BY sortOrder, addedAt")
    suspend fun getAll(): List<SourceConfigEntity>

    @Query("SELECT * FROM manga_sources WHERE id = :id")
    suspend fun get(id: String): SourceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(config: SourceConfigEntity)

    @Upsert
    suspend fun upsert(config: SourceConfigEntity)

    @Query("UPDATE manga_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE manga_sources SET lastError = :error, lastErrorAt = :at WHERE id = :id")
    suspend fun setHealth(id: String, error: String?, at: Long?)

    @Query("DELETE FROM manga_sources WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MangaDao {
    // Series

    @Query("SELECT * FROM manga_series WHERE id = :id")
    suspend fun getSeries(id: Long): MangaSeries?

    @Query("SELECT * FROM manga_series WHERE id = :id")
    fun observeSeries(id: Long): Flow<MangaSeries?>

    @Query("SELECT * FROM manga_series WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun findSeries(sourceId: String, sourceKey: String): MangaSeries?

    @Query("SELECT * FROM manga_series WHERE favorite = 1")
    suspend fun getFavorites(): List<MangaSeries>

    @Insert
    suspend fun insertSeries(series: MangaSeries): Long

    @Update
    suspend fun updateSeries(series: MangaSeries)

    @Query("UPDATE manga_series SET favorite = :favorite, favoritedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, at: Long?)

    @Query("UPDATE manga_series SET lastReadAt = :at WHERE id = :id")
    suspend fun setSeriesLastRead(id: Long, at: Long)

    @Query("DELETE FROM manga_series WHERE id = :id")
    suspend fun deleteSeries(id: Long)

    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM manga_chapters c WHERE c.seriesId = s.id AND c.read = 0) AS unreadCount,
            (SELECT COUNT(*) FROM manga_chapters c WHERE c.seriesId = s.id) AS chapterCount,
            (SELECT COUNT(*) FROM manga_downloads d WHERE d.seriesId = s.id AND d.state = 'COMPLETED') AS downloadedCount
        FROM manga_series s
        WHERE s.favorite = 1
        ORDER BY COALESCE(s.lastReadAt, s.favoritedAt, s.createdAt) DESC
        """,
    )
    fun observeLibrary(): Flow<List<LibrarySeries>>

    // Chapters

    @Query("SELECT * FROM manga_chapters WHERE seriesId = :seriesId ORDER BY sortIndex")
    fun observeChapters(seriesId: Long): Flow<List<MangaChapter>>

    @Query("SELECT * FROM manga_chapters WHERE seriesId = :seriesId ORDER BY sortIndex")
    suspend fun getChapters(seriesId: Long): List<MangaChapter>

    @Query("SELECT * FROM manga_chapters WHERE id = :id")
    suspend fun getChapter(id: Long): MangaChapter?

    @Query("SELECT * FROM manga_chapters WHERE id IN (:ids)")
    suspend fun getChapters(ids: List<Long>): List<MangaChapter>

    @Insert
    suspend fun insertChapters(chapters: List<MangaChapter>)

    @Update
    suspend fun updateChapters(chapters: List<MangaChapter>)

    @Query("UPDATE manga_chapters SET read = :read, readAt = :at WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean, at: Long?)

    @Query("UPDATE manga_chapters SET lastPageRead = :page, pageOffset = :offset, pageCount = :pageCount WHERE id = :id")
    suspend fun setProgress(id: Long, page: Int, offset: Float, pageCount: Int?)

    @Query("DELETE FROM manga_chapters WHERE id IN (:ids)")
    suspend fun deleteChapters(ids: List<Long>)

    /** Inserts new chapters and refreshes metadata of known ones, keeping read state and progress. */
    @Transaction
    suspend fun mergeChapters(seriesId: Long, incoming: List<MangaChapter>, now: Long): MergeResult {
        val existing = getChapters(seriesId).associateBy { it.sourceKey }
        val toInsert = mutableListOf<MangaChapter>()
        val toUpdate = mutableListOf<MangaChapter>()
        incoming.forEach { chapter ->
            val known = existing[chapter.sourceKey]
            if (known == null) {
                toInsert += chapter.copy(id = 0, seriesId = seriesId, addedAt = now)
            } else {
                val refreshed = known.copy(
                    name = chapter.name,
                    number = chapter.number,
                    volume = chapter.volume,
                    scanlator = chapter.scanlator,
                    uploadedAt = chapter.uploadedAt,
                    sortIndex = chapter.sortIndex,
                    pageCount = chapter.pageCount ?: known.pageCount,
                )
                if (refreshed != known) toUpdate += refreshed
            }
        }
        val incomingKeys = incoming.mapTo(HashSet()) { it.sourceKey }
        // Chapters the source no longer lists are dropped unless the user has read or downloaded them.
        val removed = existing.values.filter { it.sourceKey !in incomingKeys }
        val downloaded = getDownloadedChapterIds(seriesId).toSet()
        val toDelete = removed.filter { !it.read && it.id !in downloaded }.map { it.id }
        if (toInsert.isNotEmpty()) insertChapters(toInsert)
        if (toUpdate.isNotEmpty()) updateChapters(toUpdate)
        if (toDelete.isNotEmpty()) deleteChapters(toDelete)
        return MergeResult(added = toInsert.size, updated = toUpdate.size, removed = toDelete.size)
    }

    @Query("SELECT chapterId FROM manga_downloads WHERE seriesId = :seriesId")
    suspend fun getDownloadedChapterIds(seriesId: Long): List<Long>

    // History

    @Upsert
    suspend fun upsertHistory(history: MangaHistory)

    @Query(
        """
        SELECT s.id AS seriesId, s.title AS seriesTitle, s.coverUrl AS coverUrl, s.sourceId AS sourceId,
            c.id AS chapterId, c.name AS chapterName, c.read AS chapterRead, c.lastPageRead AS lastPageRead,
            c.pageCount AS pageCount, h.readAt AS readAt
        FROM manga_history h
        JOIN manga_series s ON s.id = h.seriesId
        JOIN manga_chapters c ON c.id = h.chapterId
        WHERE h.readAt = (SELECT MAX(h2.readAt) FROM manga_history h2 WHERE h2.seriesId = h.seriesId)
        ORDER BY h.readAt DESC
        LIMIT :limit
        """,
    )
    fun observeHistory(limit: Int = 300): Flow<List<HistoryEntry>>

    @Query("DELETE FROM manga_history WHERE seriesId = :seriesId")
    suspend fun clearHistory(seriesId: Long)

    @Query("DELETE FROM manga_history")
    suspend fun clearAllHistory()
}

data class MergeResult(val added: Int, val updated: Int, val removed: Int)

@Dao
interface DownloadDao {
    @Query(
        """
        SELECT d.*, c.name AS chapterName, s.title AS seriesTitle, s.sourceId AS sourceId
        FROM manga_downloads d
        JOIN manga_chapters c ON c.id = d.chapterId
        JOIN manga_series s ON s.id = d.seriesId
        WHERE d.state != 'COMPLETED'
        ORDER BY d.queuedAt, c.sortIndex
        """,
    )
    fun observeQueue(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM manga_downloads WHERE seriesId = :seriesId")
    fun observeForSeries(seriesId: Long): Flow<List<MangaDownload>>

    @Query("SELECT * FROM manga_downloads WHERE chapterId = :chapterId")
    suspend fun get(chapterId: Long): MangaDownload?

    @Query(
        """
        SELECT d.* FROM manga_downloads d JOIN manga_chapters c ON c.id = d.chapterId
        WHERE d.state = 'QUEUED' ORDER BY d.queuedAt, c.sortIndex LIMIT 1
        """,
    )
    suspend fun nextQueued(): MangaDownload?

    /** Atomically takes the next queued download and marks it as downloading. */
    @Transaction
    suspend fun claimNext(now: Long): MangaDownload? {
        val next = nextQueued() ?: return null
        setState(next.chapterId, DownloadState.DOWNLOADING, now)
        return next.copy(state = DownloadState.DOWNLOADING, updatedAt = now)
    }

    @Query("UPDATE manga_downloads SET state = 'FAILED', error = :error, attempts = attempts + 1, updatedAt = :at WHERE chapterId = :chapterId")
    suspend fun fail(chapterId: Long, error: String, at: Long)

    @Query("SELECT * FROM manga_downloads WHERE chapterId IN (:chapterIds)")
    suspend fun getAll(chapterIds: List<Long>): List<MangaDownload>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(downloads: List<MangaDownload>): List<Long>

    @Update
    suspend fun update(download: MangaDownload)

    @Query("UPDATE manga_downloads SET state = :state, updatedAt = :at WHERE chapterId = :chapterId")
    suspend fun setState(chapterId: Long, state: DownloadState, at: Long)

    @Query("UPDATE manga_downloads SET downloadedPages = :done, totalPages = :total, updatedAt = :at WHERE chapterId = :chapterId")
    suspend fun setProgress(chapterId: Long, done: Int, total: Int, at: Long)

    @Query("UPDATE manga_downloads SET state = :to, updatedAt = :at WHERE state IN (:from)")
    suspend fun moveStates(from: List<DownloadState>, to: DownloadState, at: Long)

    @Query("UPDATE manga_downloads SET state = 'QUEUED', error = NULL, attempts = 0, updatedAt = :at WHERE chapterId = :chapterId")
    suspend fun requeue(chapterId: Long, at: Long)

    @Query("DELETE FROM manga_downloads WHERE chapterId IN (:chapterIds)")
    suspend fun delete(chapterIds: List<Long>)

    @Query("SELECT COUNT(*) FROM manga_downloads WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun activeCount(): Int
}
