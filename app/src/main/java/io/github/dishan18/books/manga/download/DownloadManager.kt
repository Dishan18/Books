package io.github.dishan18.books.manga.download

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.dishan18.books.manga.data.DownloadDao
import io.github.dishan18.books.manga.data.DownloadItem
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadSettings(val chapterConcurrency: Int = 2, val wifiOnly: Boolean = false)

/**
 * Public face of the downloader: queues chapters in Room and keeps one WorkManager job running
 * while there is work. Pausing is cooperative: the worker notices between pages.
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadDao,
    val storage: DownloadStorage,
) {
    private val prefs = context.getSharedPreferences("manga_downloads", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        DownloadSettings(
            chapterConcurrency = prefs.getInt("concurrency", 2).coerceIn(1, 4),
            wifiOnly = prefs.getBoolean("wifiOnly", false),
        ),
    )
    val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    val queue: Flow<List<DownloadItem>> = dao.observeQueue()

    fun observeForSeries(seriesId: Long): Flow<List<MangaDownload>> = dao.observeForSeries(seriesId)

    /** Queues chapters that aren't downloaded yet; failed or paused ones are re-queued. */
    suspend fun enqueue(chapters: List<MangaChapter>) {
        if (chapters.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = dao.getAll(chapters.map { it.id }).associateBy { it.chapterId }
        val fresh = chapters.filter { it.id !in existing }.mapIndexed { i, chapter ->
            MangaDownload(chapterId = chapter.id, seriesId = chapter.seriesId, state = DownloadState.QUEUED, queuedAt = now + i, updatedAt = now)
        }
        dao.insertAll(fresh)
        existing.values.filter { it.state == DownloadState.FAILED || it.state == DownloadState.PAUSED }
            .forEach { dao.requeue(it.chapterId, now) }
        start()
    }

    suspend fun pause(chapterId: Long) {
        val download = dao.get(chapterId) ?: return
        if (download.state == DownloadState.QUEUED || download.state == DownloadState.DOWNLOADING) {
            dao.setState(chapterId, DownloadState.PAUSED, System.currentTimeMillis())
        }
    }

    suspend fun pauseAll() =
        dao.moveStates(listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING), DownloadState.PAUSED, System.currentTimeMillis())

    suspend fun resume(chapterId: Long) {
        dao.requeue(chapterId, System.currentTimeMillis())
        start()
    }

    suspend fun resumeAll() {
        dao.moveStates(listOf(DownloadState.PAUSED, DownloadState.FAILED), DownloadState.QUEUED, System.currentTimeMillis())
        start()
    }

    /** Removes downloads (queued or finished) and their files. */
    suspend fun remove(downloads: List<Pair<Long, Long>>) {
        if (downloads.isEmpty()) return
        dao.delete(downloads.map { it.second })
        downloads.forEach { (seriesId, chapterId) -> storage.delete(seriesId, chapterId) }
    }

    /** Called at startup: downloads left "in progress" by a killed process go back in the queue. */
    suspend fun recover() {
        dao.moveStates(listOf(DownloadState.DOWNLOADING), DownloadState.QUEUED, System.currentTimeMillis())
        if (dao.activeCount() > 0) start()
    }

    fun updateSettings(settings: DownloadSettings) {
        val clamped = settings.copy(chapterConcurrency = settings.chapterConcurrency.coerceIn(1, 4))
        prefs.edit {
            putInt("concurrency", clamped.chapterConcurrency)
            putBoolean("wifiOnly", clamped.wifiOnly)
        }
        _settings.value = clamped
    }

    /** Ensures a worker runs after any current one, so newly queued chapters are never missed. */
    fun start() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "manga-downloads"
    }
}
