package io.github.dishan18.books.manga.data

import io.github.dishan18.books.manga.data.MangaRepository.Companion.resumeTarget
import io.github.dishan18.books.manga.data.MangaRepository.Companion.toEntities
import io.github.dishan18.books.manga.source.api.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTargetTest {

    private fun chapter(index: Int, read: Boolean = false, page: Int = 0) =
        MangaChapter(id = index + 1L, seriesId = 1, sourceKey = "c$index", name = "Ch $index", sortIndex = index, read = read, lastPageRead = page, addedAt = 0)

    @Test
    fun emptyListHasNoTarget() = assertNull(resumeTarget(emptyList()))

    @Test
    fun unstartedSeriesStartsAtTheBeginning() =
        assertEquals(0, resumeTarget(List(3) { chapter(it) })!!.sortIndex)

    @Test
    fun chapterInProgressWins() =
        assertEquals(1, resumeTarget(listOf(chapter(0, read = true), chapter(1, page = 4), chapter(2)))!!.sortIndex)

    @Test
    fun nextUnreadAfterFurthestRead() =
        assertEquals(3, resumeTarget(listOf(chapter(0, true), chapter(1), chapter(2, true), chapter(3)))!!.sortIndex)

    @Test
    fun everythingReadReopensTheLastChapter() =
        assertEquals(2, resumeTarget(List(3) { chapter(it, read = true) })!!.sortIndex)

    @Test
    fun sourceOrderBecomesSortIndex() {
        val entities = listOf(
            SourceChapter("a", "One", 1f, null, null, null, null),
            SourceChapter("b", "Two", 2f, null, "Group", 5L, 20),
        ).toEntities(seriesId = 9, now = 100)
        assertEquals(listOf(0, 1), entities.map { it.sortIndex })
        assertEquals(9L, entities[1].seriesId)
        assertEquals(20, entities[1].pageCount)
    }
}
