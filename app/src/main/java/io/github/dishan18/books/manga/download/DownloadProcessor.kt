package io.github.dishan18.books.manga.download

import io.github.dishan18.books.manga.data.DownloadDao
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.data.MangaDao
import io.github.dishan18.books.manga.data.MangaDownload
import io.github.dishan18.books.manga.source.api.MangaSource
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.SourcePage
import io.github.dishan18.books.manga.source.api.toSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the worker shows in its notification. */
data class DownloadStatus(val activeTitles: List<String>, val pagesDone: Int, val pagesTotal: Int)

/**
 * Works through the download queue stored in Room. Runs [chapterConcurrency] chapters at once,
 * fetching up to [pageConcurrency] pages per chapter in parallel, and keeps pages already on disk
 * so interrupted chapters resume where they stopped.
 */
class DownloadProcessor(
    private val downloads: DownloadDao,
    private val manga: MangaDao,
    private val sourceFor: suspend (String) -> MangaSource,
    private val storage: DownloadStorage,
    private val chapterConcurrency: Int,
    private val pageConcurrency: Int = 3,
    private val maxAttempts: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: suspend (attempt: Int) -> Unit = { attempt -> delay(1_000L shl (attempt - 1)) },
) {
    /** Thrown inside a chapter when the user paused or removed it mid-download. */
    private class Interrupted : CancellationException("Download paused or removed")

    enum class Outcome { DONE, RETRY_LATER }

    private val statusLock = Mutex()
    private val active = linkedMapOf<Long, Triple<String, Int, Int>>()

    /** Processes queued downloads until none are left. */
    suspend fun run(onStatus: suspend (DownloadStatus) -> Unit): Outcome {
        // Once the device is offline, stop claiming work: re-queued chapters would just bounce
        // straight back. WorkManager reruns us when the network returns.
        val offline = AtomicBoolean(false)
        coroutineScope {
            repeat(chapterConcurrency.coerceIn(1, 4)) {
                launch {
                    while (!offline.get()) {
                        val next = downloads.claimNext(clock()) ?: break
                        if (!downloadChapter(next, onStatus)) offline.set(true)
                    }
                }
            }
        }
        return if (offline.get()) Outcome.RETRY_LATER else Outcome.DONE
    }

    /** Returns false when the chapter went back in the queue because the device is offline. */
    private suspend fun downloadChapter(download: MangaDownload, onStatus: suspend (DownloadStatus) -> Unit): Boolean {
        val chapterId = download.chapterId
        val chapter = manga.getChapter(chapterId)
        val series = chapter?.let { manga.getSeries(it.seriesId) }
        if (chapter == null || series == null) {
            downloads.delete(listOf(chapterId))
            return true
        }
        val title = "${series.title} · ${chapter.name}"
        try {
            val source = sourceFor(series.sourceId)
            val pageList = PageList(withRetries { source.pages(series.sourceKey, chapter.sourceKey) }) {
                source.pages(series.sourceKey, chapter.sourceKey)
            }
            val total = pageList.size()
            if (total == 0) throw SourceException(SourceError.Other("This chapter has no pages"))
            val done = AtomicInteger((0 until total).count { storage.pageFile(series.id, chapterId, it).hasContent() })
            downloads.setProgress(chapterId, done.get(), total, clock())
            report(chapterId, title, done.get(), total, onStatus)

            val permits = Semaphore(pageConcurrency)
            coroutineScope {
                for (index in 0 until total) {
                    val file = storage.pageFile(series.id, chapterId, index)
                    if (file.hasContent()) continue
                    launch {
                        permits.withPermit {
                            ensureStillWanted(chapterId)
                            fetchPage(source, pageList, index, file)
                            val count = done.incrementAndGet()
                            downloads.setProgress(chapterId, count, total, clock())
                            report(chapterId, title, count, total, onStatus)
                        }
                    }
                }
            }
            ensureStillWanted(chapterId)
            storage.markComplete(series.id, chapterId, total)
            downloads.setState(chapterId, DownloadState.COMPLETED, clock())
            if (chapter.pageCount != total) manga.setProgress(chapterId, chapter.lastPageRead, chapter.pageOffset, total)
            return true
        } catch (e: Interrupted) {
            // Paused: progress stays on disk. Removed: the manager already deleted the files.
            return true
        } catch (e: CancellationException) {
            // The system stopped the worker: put the chapter back so the next run resumes it.
            downloads.setState(chapterId, DownloadState.QUEUED, clock())
            throw e
        } catch (e: Exception) {
            val error = e.toSourceException().error
            return if (error == SourceError.Offline) {
                downloads.setState(chapterId, DownloadState.QUEUED, clock())
                false
            } else {
                downloads.fail(chapterId, error.message, clock())
                true
            }
        } finally {
            statusLock.withLock { active.remove(chapterId) }
        }
    }

    /**
     * A chapter's page list that can be re-requested once when its URLs expire (MangaDex@Home
     * URLs are short-lived). Parallel page fetches share one refresh.
     */
    private class PageList(initial: List<SourcePage>, private val reload: suspend () -> List<SourcePage>) {
        private val lock = Mutex()
        private var pages = initial
        private var generation = 0

        suspend fun size() = lock.withLock { pages.size }

        suspend fun get(index: Int): Pair<SourcePage, Int> = lock.withLock {
            (pages.getOrNull(index) ?: throw SourceException(SourceError.NotFound)) to generation
        }

        /** Reloads unless another fetch already did since [seenGeneration]. Returns false after one reload. */
        suspend fun refresh(seenGeneration: Int): Boolean = lock.withLock {
            if (generation != seenGeneration) return true
            if (generation >= 1) return false
            pages = reload()
            generation++
            true
        }
    }

    private suspend fun fetchPage(source: MangaSource, pageList: PageList, index: Int, file: File) {
        var attempt = 0
        while (true) {
            attempt++
            val (page, generation) = pageList.get(index)
            try {
                source.fetchPage(page, file)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toSourceException().error
                val expired = error == SourceError.Forbidden || error == SourceError.NotFound || error == SourceError.Http(410)
                if (expired && pageList.refresh(generation)) continue
                if (attempt >= maxAttempts || !error.isRetryable()) throw e.toSourceException()
                backoff(attempt)
            }
        }
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toSourceException().error
                if (attempt >= maxAttempts || !error.isRetryable()) throw e.toSourceException()
                backoff(attempt)
            }
        }
    }

    private suspend fun ensureStillWanted(chapterId: Long) {
        if (downloads.get(chapterId)?.state != DownloadState.DOWNLOADING) throw Interrupted()
    }

    private suspend fun report(chapterId: Long, title: String, done: Int, total: Int, onStatus: suspend (DownloadStatus) -> Unit) {
        val status = statusLock.withLock {
            active[chapterId] = Triple(title, done, total)
            DownloadStatus(
                activeTitles = active.values.map { it.first },
                pagesDone = active.values.sumOf { it.second },
                pagesTotal = active.values.sumOf { it.third },
            )
        }
        onStatus(status)
    }

    private fun File.hasContent() = isFile && length() > 0

    private fun SourceError.isRetryable() = when (this) {
        SourceError.Timeout, SourceError.Unreachable, SourceError.InvalidResponse, is SourceError.RateLimited -> true
        is SourceError.Http -> code >= 500
        else -> false
    }
}
