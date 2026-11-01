package io.github.dishan18.books.discover

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.dishan18.books.app
import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.data.CatalogInfo
import io.github.dishan18.books.discover.catalog.BookFile
import io.github.dishan18.books.discover.catalog.OnlineBook
import io.github.dishan18.books.manga.download.MangaNotifications
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.toSourceException
import io.github.dishan18.books.manga.source.net.await
import io.github.dishan18.books.manga.source.net.download
import io.github.dishan18.books.manga.source.net.requireSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface BookDownloadStatus {
    data object Idle : BookDownloadStatus
    data object Queued : BookDownloadStatus
    /** [fraction] is 0..1, or null while the size is unknown. */
    data class Running(val fraction: Float?) : BookDownloadStatus
    data class Failed(val message: String) : BookDownloadStatus
    data class Done(val bookId: String) : BookDownloadStatus
}

/** Book downloads run through WorkManager, so they finish even if the app is closed. */
class BookDownloads(context: Context) {
    private val work = WorkManager.getInstance(context)

    fun enqueue(book: OnlineBook, file: BookFile) {
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                workDataOf(
                    SOURCE to book.sourceId,
                    KEY to book.key,
                    TITLE to book.title,
                    AUTHOR to book.author,
                    COVER to book.coverUrl,
                    SUBJECTS to book.subjects.take(12).joinToString("\n"),
                    URL to file.url,
                    FORMAT to file.format.name,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(book.sourceId, book.key), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(sourceId: String, key: String) {
        work.cancelUniqueWork(name(sourceId, key))
    }

    fun status(sourceId: String, key: String): Flow<BookDownloadStatus> =
        work.getWorkInfosForUniqueWorkFlow(name(sourceId, key)).map { infos ->
            val info = infos.lastOrNull() ?: return@map BookDownloadStatus.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> if (info.runAttemptCount > 0) BookDownloadStatus.Running(null) else BookDownloadStatus.Queued
                WorkInfo.State.RUNNING -> {
                    val done = info.progress.getLong(DONE, 0)
                    val total = info.progress.getLong(TOTAL, -1)
                    BookDownloadStatus.Running(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                }
                WorkInfo.State.SUCCEEDED -> info.outputData.getString(BOOK_ID)?.let { BookDownloadStatus.Done(it) } ?: BookDownloadStatus.Idle
                WorkInfo.State.FAILED -> BookDownloadStatus.Failed(info.outputData.getString(ERROR) ?: "The download failed")
                WorkInfo.State.CANCELLED -> BookDownloadStatus.Idle
            }
        }

    internal companion object {
        const val TAG = "book-download"
        const val SOURCE = "source"
        const val KEY = "key"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val COVER = "cover"
        const val SUBJECTS = "subjects"
        const val URL = "url"
        const val FORMAT = "format"
        const val DONE = "done"
        const val TOTAL = "total"
        const val BOOK_ID = "bookId"
        const val ERROR = "error"

        fun name(sourceId: String, key: String) = "book:$sourceId:$key"
    }
}

/** Downloads one book file, checks it really is an EPUB or PDF, and adds it to the library. */
class BookDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val title get() = inputData.getString(BookDownloads.TITLE).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val sourceId = inputData.getString(BookDownloads.SOURCE) ?: return failure("Missing catalog")
        val key = inputData.getString(BookDownloads.KEY) ?: return failure("Missing book")
        val url = inputData.getString(BookDownloads.URL) ?: return failure("Missing file")
        val format = inputData.getString(BookDownloads.FORMAT)?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() }
            ?: return failure("Unsupported format")

        app.repository.findDownloaded(sourceId, key)?.let { return Result.success(workDataOf(BookDownloads.BOOK_ID to it.id)) }
        val client = app.discover.sources.client(sourceId) ?: return failure("This catalog was removed")

        MangaNotifications.ensureChannels(applicationContext)
        runCatching { setForeground(getForegroundInfo()) }

        val dir = File(applicationContext.cacheDir, "book-downloads").apply { mkdirs() }
        val temp = File(dir, "$id.download")
        return try {
            var lastProgress = 0L
            var lastNotification = 0L
            client.download(Request.Builder().url(url).build(), temp) { read, total ->
                if (read > MAX_BYTES) throw SourceException(SourceError.Other("This file is too large"))
                val now = System.currentTimeMillis()
                // Progress at most twice a second, the notification once a second: each update costs a wake-up.
                if (now - lastProgress >= 500 || read == total) {
                    lastProgress = now
                    setProgressAsync(workDataOf(BookDownloads.DONE to read, BookDownloads.TOTAL to total))
                }
                if (now - lastNotification >= 1_000) {
                    lastNotification = now
                    runCatching { setForegroundAsync(foregroundInfo(read, total)) }
                }
            }
            if (!looksLike(temp, format)) {
                temp.delete()
                return failure("The catalog sent something that isn't ${if (format == BookFormat.EPUB) "an EPUB" else "a PDF"}")
            }
            val info = CatalogInfo(
                sourceId = sourceId,
                sourceKey = key,
                title = title.ifBlank { "Untitled" },
                author = inputData.getString(BookDownloads.AUTHOR),
                subjects = inputData.getString(BookDownloads.SUBJECTS).orEmpty().split('\n').filter { it.isNotBlank() },
                coverUrl = inputData.getString(BookDownloads.COVER),
            )
            val book = app.repository.addDownloaded(temp, format, info) { coverUrl -> fetchBytes(client, coverUrl) }.getOrElse {
                return failure("Couldn't open the downloaded book: ${it.message.orEmpty()}")
            }
            MangaNotifications.notify(
                applicationContext,
                NOTIFY_BASE + (book.id.hashCode() and 0xFFFF),
                MangaNotifications.DOWNLOADS_CHANNEL,
                "Added to your library",
                book.title,
            )
            Result.success(workDataOf(BookDownloads.BOOK_ID to book.id))
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            val error = e.toSourceException().error
            val retryable = error == SourceError.Offline || error == SourceError.Timeout || error == SourceError.Unreachable ||
                (error is SourceError.Http && error.code >= 500)
            if (retryable && runAttemptCount < 3) Result.retry() else failure(error.message)
        }
    }

    private suspend fun fetchBytes(client: okhttp3.OkHttpClient, url: String): ByteArray? {
        val response = client.newCall(Request.Builder().url(url).build()).await().requireSuccess()
        return withContext(Dispatchers.IO) {
            response.use { body -> body.body.bytes().takeIf { it.size in 1..MAX_COVER_BYTES } }
        }
    }

    /** EPUBs are ZIP files ("PK"); PDFs start with "%PDF". Anything else (say, a sign-in page) is refused. */
    private fun looksLike(file: File, format: BookFormat): Boolean {
        val head = ByteArray(5)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return false
        return when (format) {
            BookFormat.EPUB -> head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            BookFormat.PDF -> String(head, 0, 4, Charsets.US_ASCII) == "%PDF"
        }
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(BookDownloads.ERROR, message).build())

    private fun foregroundInfo(done: Long, total: Long) = ForegroundInfo(
        FOREGROUND_ID,
        MangaNotifications.progress(
            applicationContext,
            MangaNotifications.DOWNLOADS_CHANNEL,
            "Downloading ${title.ifBlank { "a book" }}",
            null,
            (done / 1024).toInt(),
            (total / 1024).toInt(),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val FOREGROUND_ID = 1004
        const val NOTIFY_BASE = 20_000
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_COVER_BYTES = 5 * 1024 * 1024
    }
}
