package io.github.dishan18.books.manga.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.dishan18.books.app

/** Runs the download queue as a foreground "data sync" job with a progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing downloads", null, 0, 0)

    override suspend fun doWork(): Result {
        val module = applicationContext.app.manga
        MangaNotifications.ensureChannels(applicationContext)
        // If the system won't let us start in the foreground right now, carry on in the background.
        runCatching { setForeground(getForegroundInfo()) }

        var lastUpdate = 0L
        val processor = DownloadProcessor(
            downloads = module.database.downloads(),
            manga = module.database.manga(),
            sourceFor = module.sources::require,
            storage = module.downloads.storage,
            chapterConcurrency = module.downloads.settings.value.chapterConcurrency,
        )
        val outcome = processor.run { status ->
            val now = System.currentTimeMillis()
            // At most one notification refresh per second: each one wakes the system UI.
            if (now - lastUpdate < 1_000 && status.pagesDone < status.pagesTotal) return@run
            lastUpdate = now
            val title = when (status.activeTitles.size) {
                0 -> "Downloading"
                1 -> "Downloading ${status.activeTitles.first()}"
                else -> "Downloading ${status.activeTitles.size} chapters"
            }
            runCatching { setForeground(foregroundInfo(title, status.activeTitles.drop(1).joinToString(), status.pagesDone, status.pagesTotal)) }
        }
        return when (outcome) {
            DownloadProcessor.Outcome.DONE -> Result.success()
            DownloadProcessor.Outcome.RETRY_LATER -> Result.retry()
        }
    }

    private fun foregroundInfo(title: String, text: String?, done: Int, total: Int) = ForegroundInfo(
        MangaNotifications.DOWNLOAD_ID,
        MangaNotifications.progress(applicationContext, MangaNotifications.DOWNLOADS_CHANNEL, title, text?.takeIf { it.isNotBlank() }, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
