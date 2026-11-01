package io.github.dishan18.books.manga.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.dishan18.books.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Re-fetches the chapter list of every series in the manga library. A failing source only fails
 * its own series; the rest still update.
 */
class LibraryUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo() = ForegroundInfo(
        MangaNotifications.UPDATE_ID,
        MangaNotifications.progress(applicationContext, MangaNotifications.UPDATES_CHANNEL, "Checking for new chapters", null, 0, 0),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val module = applicationContext.app.manga
        MangaNotifications.ensureChannels(applicationContext)
        val favorites = module.database.manga().getFavorites()
        val newChapters = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val done = AtomicInteger(0)
        val updatedTitles = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val permits = Semaphore(3)
        coroutineScope {
            favorites.map { series ->
                async {
                    permits.withPermit {
                        module.repository.refreshChapters(series.id)
                            .onSuccess {
                                if (it.added > 0) {
                                    newChapters.addAndGet(it.added)
                                    updatedTitles += series.title
                                }
                            }
                            .onFailure { failed.incrementAndGet() }
                        setProgress(workDataOf(KEY_DONE to done.incrementAndGet(), KEY_TOTAL to favorites.size))
                    }
                }
            }.awaitAll()
        }
        if (newChapters.get() > 0) {
            MangaNotifications.notify(
                applicationContext,
                MangaNotifications.UPDATE_RESULT_ID,
                MangaNotifications.UPDATES_CHANNEL,
                "${newChapters.get()} new chapter${if (newChapters.get() == 1) "" else "s"}",
                updatedTitles.take(5).joinToString(", "),
            )
        }
        return Result.success(workDataOf(KEY_NEW to newChapters.get(), KEY_FAILED to failed.get()))
    }

    companion object {
        const val WORK_NAME = "manga-library-update"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_NEW = "new"
        const val KEY_FAILED = "failed"

        /**
         * [userInitiated] work (pull to refresh) runs right away; automatic checks also wait until
         * the battery isn't low, as Android's background-work guidance recommends.
         */
        fun start(context: Context, userInitiated: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(!userInitiated)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
