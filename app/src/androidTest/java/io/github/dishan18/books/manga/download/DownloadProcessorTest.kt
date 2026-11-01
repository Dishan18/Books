package io.github.dishan18.books.manga.download

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.manga.FakeSource
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaDownload
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.source.api.SourceError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class DownloadProcessorTest {

    private lateinit var db: BooksDatabase
    private lateinit var storage: DownloadStorage
    private lateinit var root: File
    private val source = FakeSource(pagesPerChapter = 5)
    private var seriesId = 0L
    private val chapterIds = mutableListOf<Long>()

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "download-test-${System.nanoTime()}")
        storage = DownloadStorage(root)
        seriesId = db.manga().insertSeries(MangaSeries(sourceId = source.id, sourceKey = "s1", title = "Series", createdAt = 0))
        db.manga().insertChapters((0..2).map { MangaChapter(seriesId = seriesId, sourceKey = "c$it", name = "Ch $it", sortIndex = it, addedAt = 0) })
        chapterIds += db.manga().getChapters(seriesId).map { it.id }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun processor(concurrency: Int = 2) = DownloadProcessor(
        downloads = db.downloads(),
        manga = db.manga(),
        sourceFor = { source },
        storage = storage,
        chapterConcurrency = concurrency,
        backoff = {},
    )

    private suspend fun queue(vararg ids: Long) =
        db.downloads().insertAll(ids.mapIndexed { i, id -> MangaDownload(id, seriesId, DownloadState.QUEUED, queuedAt = i.toLong(), updatedAt = 0) })

    @Test
    fun downloadsAllQueuedChaptersCompletely() = runBlocking {
        queue(*chapterIds.toLongArray())
        val outcome = processor().run { }

        assertEquals(DownloadProcessor.Outcome.DONE, outcome)
        chapterIds.forEach { id ->
            assertEquals(DownloadState.COMPLETED, db.downloads().get(id)!!.state)
            assertEquals(5, storage.completePages(seriesId, id)!!.size)
        }
        assertEquals(15, source.fetches.get())
        assertEquals(5, db.manga().getChapter(chapterIds[0])!!.pageCount)
    }

    @Test
    fun resumeKeepsPagesAlreadyOnDisk() = runBlocking {
        val id = chapterIds[0]
        (0 until 3).forEach { storage.pageFile(seriesId, id, it).apply { parentFile!!.mkdirs(); writeText("old") } }
        queue(id)
        processor().run { }

        assertEquals("only the two missing pages are fetched", 2, source.fetches.get())
        assertEquals("old", storage.pageFile(seriesId, id, 0).readText())
        assertTrue(storage.isComplete(seriesId, id))
    }

    @Test
    fun transientErrorsAreRetried() = runBlocking {
        val attempts = ConcurrentHashMap<String, Int>()
        source.failure = { url ->
            val n = attempts.merge(url.substringBefore('@'), 1, Int::plus)!!
            if (url.startsWith("c0/2") && n < 3) SourceError.Timeout else null
        }
        queue(chapterIds[0])
        processor().run { }
        assertEquals(DownloadState.COMPLETED, db.downloads().get(chapterIds[0])!!.state)
    }

    @Test
    fun expiredPageUrlsAreRefreshedOnce() = runBlocking {
        // URLs from the first page list are "expired"; the refreshed list works.
        source.failure = { url -> if (url.endsWith("@1")) SourceError.Forbidden else null }
        queue(chapterIds[0])
        processor().run { }

        assertEquals(DownloadState.COMPLETED, db.downloads().get(chapterIds[0])!!.state)
        assertEquals("one refresh shared by all parallel page fetches", 2, source.pageListRequests.get())
    }

    @Test
    fun permanentErrorMarksChapterFailedWithoutBlockingOthers() = runBlocking {
        source.failure = { url -> if (url.startsWith("c0/")) SourceError.NotFound else null }
        queue(chapterIds[0], chapterIds[1])
        processor(concurrency = 1).run { }

        val failed = db.downloads().get(chapterIds[0])!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertNotNull(failed.error)
        assertEquals(1, failed.attempts)
        assertEquals(DownloadState.COMPLETED, db.downloads().get(chapterIds[1])!!.state)
        assertNull(storage.completePages(seriesId, chapterIds[0]))
    }

    @Test
    fun offlineChaptersGoBackInTheQueueForLater() = runBlocking {
        source.failure = { SourceError.Offline }
        queue(chapterIds[0])
        val outcome = processor().run { }

        assertEquals(DownloadProcessor.Outcome.RETRY_LATER, outcome)
        assertEquals(DownloadState.QUEUED, db.downloads().get(chapterIds[0])!!.state)
    }

    @Test
    fun pausingStopsTheChapterAndKeepsProgress() = runBlocking {
        val id = chapterIds[0]
        source.beforeFetch = { page ->
            if (page.index == 2) db.downloads().setState(id, DownloadState.PAUSED, 0)
        }
        queue(id)
        processor(concurrency = 1).run { }

        val download = db.downloads().get(id)!!
        assertEquals(DownloadState.PAUSED, download.state)
        assertTrue(storage.existingPageCount(seriesId, id) in 1..4)
        assertNull(storage.completePages(seriesId, id))
    }
}
