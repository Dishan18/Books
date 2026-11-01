package io.github.dishan18.books.manga.data

import io.github.dishan18.books.manga.source.SourceRegistry
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SeriesDetails
import io.github.dishan18.books.manga.source.api.SourceChapter
import io.github.dishan18.books.manga.source.api.SourceSeries
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.api.sourceError

/**
 * Library state for manga: turns source results into stored series and chapters, and keeps
 * favorites, read state, progress and history. All source calls report health to the registry.
 */
class MangaRepository(
    private val dao: MangaDao,
    private val registry: SourceRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeLibrary() = dao.observeLibrary()
    fun observeHistory() = dao.observeHistory()
    fun observeSeries(id: Long) = dao.observeSeries(id)
    fun observeChapters(seriesId: Long) = dao.observeChapters(seriesId)

    suspend fun getSeries(id: Long) = dao.getSeries(id)
    suspend fun getChapter(id: Long) = dao.getChapter(id)
    suspend fun getChapters(seriesId: Long) = dao.getChapters(seriesId)

    /** Stores a catalog result (or refreshes its title/cover) and returns its library id. */
    suspend fun upsertFromCatalog(sourceId: String, item: SourceSeries): Long {
        val existing = dao.findSeries(sourceId, item.key)
        if (existing != null) {
            val refreshed = existing.copy(
                title = item.title,
                coverUrl = item.coverUrl ?: existing.coverUrl,
                author = item.author ?: existing.author,
            )
            if (refreshed != existing) dao.updateSeries(refreshed)
            return existing.id
        }
        return dao.insertSeries(
            MangaSeries(
                sourceId = sourceId,
                sourceKey = item.key,
                title = item.title,
                author = item.author,
                coverUrl = item.coverUrl,
                status = item.status,
                createdAt = clock(),
            ),
        )
    }

    /** Fetches full details from the source. */
    suspend fun refreshDetails(seriesId: Long): Result<MangaSeries> {
        val series = dao.getSeries(seriesId) ?: return Result.failure(IllegalStateException("Series not found"))
        return call(series.sourceId) { source -> source.details(series.sourceKey) }.map { details ->
            val updated = series.withDetails(details)
            dao.updateSeries(updated)
            updated
        }
    }

    /** Fetches the chapter list and merges it into the library, keeping read state. */
    suspend fun refreshChapters(seriesId: Long): Result<MergeResult> {
        val series = dao.getSeries(seriesId) ?: return Result.failure(IllegalStateException("Series not found"))
        return call(series.sourceId) { source -> source.chapters(series.sourceKey) }.map { chapters ->
            val now = clock()
            val result = dao.mergeChapters(seriesId, chapters.toEntities(seriesId, now), now)
            dao.updateSeries((dao.getSeries(seriesId) ?: series).copy(chaptersUpdatedAt = now))
            result
        }
    }

    suspend fun setFavorite(seriesId: Long, favorite: Boolean) =
        dao.setFavorite(seriesId, favorite, if (favorite) clock() else null)

    suspend fun setRead(chapterIds: List<Long>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        dao.setRead(chapterIds, read, if (read) clock() else null)
        if (!read) chapterIds.forEach { dao.setProgress(it, 0, 0f, null) }
    }

    /** Marks every chapter before [chapter] in reading order as read. */
    suspend fun markPreviousRead(chapter: MangaChapter) {
        val earlier = dao.getChapters(chapter.seriesId).filter { it.sortIndex < chapter.sortIndex && !it.read }
        setRead(earlier.map { it.id }, true)
    }

    suspend fun saveProgress(chapterId: Long, page: Int, offset: Float, pageCount: Int?) =
        dao.setProgress(chapterId, page, offset.coerceIn(0f, 1f), pageCount)

    /** Records that a chapter was opened, for history and "continue reading". */
    suspend fun recordOpened(chapter: MangaChapter) {
        val now = clock()
        dao.upsertHistory(MangaHistory(chapter.id, chapter.seriesId, now))
        dao.setSeriesLastRead(chapter.seriesId, now)
    }

    suspend fun clearHistory(seriesId: Long) = dao.clearHistory(seriesId)

    /** The chapter after [chapter] in reading order, if any. */
    suspend fun nextChapter(chapter: MangaChapter): MangaChapter? =
        dao.getChapters(chapter.seriesId).firstOrNull { it.sortIndex > chapter.sortIndex }

    suspend fun previousChapter(chapter: MangaChapter): MangaChapter? =
        dao.getChapters(chapter.seriesId).lastOrNull { it.sortIndex < chapter.sortIndex }

    /** Where "continue reading" should open, or null when there are no chapters. */
    suspend fun continueChapter(seriesId: Long): MangaChapter? = resumeTarget(dao.getChapters(seriesId))

    private suspend fun <T> call(sourceId: String, block: suspend (MangaSource) -> T): Result<T> {
        val result = runSource { block(registry.require(sourceId)) }
        registry.recordResult(sourceId, result.exceptionOrNull()?.sourceError)
        return result
    }

    private fun MangaSeries.withDetails(details: SeriesDetails) = copy(
        title = details.title,
        author = details.author,
        artist = details.artist,
        description = details.description,
        coverUrl = details.coverUrl ?: coverUrl,
        status = details.status,
        genres = details.genres.joinToString("\n"),
        webUrl = details.webUrl,
        detailsUpdatedAt = clock(),
    )

    companion object {
        internal fun List<SourceChapter>.toEntities(seriesId: Long, now: Long) = mapIndexed { index, chapter ->
            MangaChapter(
                seriesId = seriesId,
                sourceKey = chapter.key,
                name = chapter.name,
                number = chapter.number,
                volume = chapter.volume,
                scanlator = chapter.scanlator,
                uploadedAt = chapter.uploadedAt,
                sortIndex = index,
                pageCount = chapter.pageCount,
                addedAt = now,
            )
        }

        /**
         * The chapter in progress if there is one, otherwise the first unread chapter after the
         * furthest one read, otherwise the first chapter.
         */
        internal fun resumeTarget(chapters: List<MangaChapter>): MangaChapter? {
            if (chapters.isEmpty()) return null
            val ordered = chapters.sortedBy { it.sortIndex }
            ordered.filter { !it.read && it.lastPageRead > 0 }.maxByOrNull { it.sortIndex }?.let { return it }
            val furthestRead = ordered.lastOrNull { it.read }
            return if (furthestRead == null) {
                ordered.first()
            } else {
                ordered.firstOrNull { it.sortIndex > furthestRead.sortIndex && !it.read }
                    ?: ordered.firstOrNull { !it.read }
                    ?: furthestRead
            }
        }
    }
}
