package io.github.dishan18.books.manga.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.data.BooksDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaDaoTest {

    private lateinit var db: BooksDatabase
    private val dao get() = db.manga()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun chapter(seriesId: Long, key: String, index: Int, name: String = "Ch $key") =
        MangaChapter(seriesId = seriesId, sourceKey = key, name = name, sortIndex = index, addedAt = 0)

    private suspend fun series(key: String, favorite: Boolean = true) =
        dao.insertSeries(MangaSeries(sourceId = "src", sourceKey = key, title = "Series $key", favorite = favorite, favoritedAt = 1, createdAt = 0))

    @Test
    fun mergeKeepsReadStateAndProgressAndAddsNewChapters() = runBlocking {
        val id = series("a")
        dao.mergeChapters(id, listOf(chapter(id, "1", 0), chapter(id, "2", 1)), now = 10)
        val first = dao.getChapters(id).first()
        dao.setRead(listOf(first.id), true, 20)
        dao.setProgress(dao.getChapters(id)[1].id, 7, 0.5f, 30)

        val result = dao.mergeChapters(
            id,
            listOf(chapter(id, "1", 0, name = "Ch 1 (renamed)"), chapter(id, "2", 1), chapter(id, "3", 2)),
            now = 40,
        )

        assertEquals(1, result.added)
        val chapters = dao.getChapters(id)
        assertEquals(listOf("Ch 1 (renamed)", "Ch 2", "Ch 3"), chapters.map { it.name })
        assertTrue(chapters[0].read)
        assertEquals(7, chapters[1].lastPageRead)
        assertEquals(40L, chapters[2].addedAt)
    }

    @Test
    fun mergeDropsVanishedUnreadChaptersButKeepsReadOnes() = runBlocking {
        val id = series("a")
        dao.mergeChapters(id, listOf(chapter(id, "1", 0), chapter(id, "2", 1), chapter(id, "3", 2)), now = 0)
        dao.setRead(listOf(dao.getChapters(id)[0].id), true, 1)

        val result = dao.mergeChapters(id, listOf(chapter(id, "3", 0)), now = 2)

        assertEquals(1, result.removed)
        assertEquals(listOf("1", "3"), dao.getChapters(id).map { it.sourceKey }.sorted())
    }

    @Test
    fun libraryCountsUnreadChaptersOfFavoritesOnly() = runBlocking {
        val a = series("a")
        val b = series("b", favorite = false)
        dao.insertChapters(listOf(chapter(a, "1", 0), chapter(a, "2", 1), chapter(b, "1", 0)))
        dao.setRead(listOf(dao.getChapters(a).first().id), true, 1)

        val library = dao.observeLibrary().first()
        assertEquals(listOf(a), library.map { it.series.id })
        assertEquals(1, library.single().unreadCount)
        assertEquals(2, library.single().chapterCount)
    }

    @Test
    fun historyShowsLatestChapterPerSeries() = runBlocking {
        val a = series("a")
        val b = series("b")
        dao.insertChapters(listOf(chapter(a, "1", 0), chapter(a, "2", 1), chapter(b, "1", 0)))
        val (a1, a2) = dao.getChapters(a)
        val b1 = dao.getChapters(b).single()
        dao.upsertHistory(MangaHistory(a1.id, a, 100))
        dao.upsertHistory(MangaHistory(b1.id, b, 200))
        dao.upsertHistory(MangaHistory(a2.id, a, 300))

        val history = dao.observeHistory().first()
        assertEquals(listOf(a to a2.id, b to b1.id), history.map { it.seriesId to it.chapterId })
    }

    @Test
    fun deletingASeriesCascadesToChaptersHistoryAndDownloads() = runBlocking {
        val a = series("a")
        dao.insertChapters(listOf(chapter(a, "1", 0)))
        val c = dao.getChapters(a).single()
        dao.upsertHistory(MangaHistory(c.id, a, 1))
        db.downloads().insertAll(listOf(MangaDownload(c.id, a, DownloadState.COMPLETED, queuedAt = 0, updatedAt = 0)))

        dao.deleteSeries(a)

        assertTrue(dao.getChapters(a).isEmpty())
        assertTrue(dao.observeHistory().first().isEmpty())
        assertEquals(null, db.downloads().get(c.id))
    }
}
