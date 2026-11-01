package io.github.dishan18.books.year

import io.github.dishan18.books.rewards.data.FinishKind
import io.github.dishan18.books.rewards.data.FinishedItem
import io.github.dishan18.books.rewards.data.ReadingDay
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.rewards.data.ReadingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class YearRecapTest {
    private val zone = ZoneOffset.UTC
    private fun day(month: Int, dayOfMonth: Int, year: Int = 2026) = LocalDate.of(year, month, dayOfMonth).toEpochDay()
    private fun millis(day: Long, hour: Int) = LocalDate.ofEpochDay(day).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun session(kind: ReadingKind, id: String, title: String, day: Long, hour: Int, seconds: Long) =
        ReadingSession(kind = kind, itemId = id, title = title, startedAt = millis(day, hour), endedAt = millis(day, hour) + seconds * 1000, activeSeconds = seconds, day = day)

    @Test
    fun totalsComeOnlyFromTheChosenYear() {
        val days = listOf(
            ReadingDay(day(12, 31, 2025), 9_000, goalMet = true),
            ReadingDay(day(3, 1), 600, goalMet = true, pages = 12),
            ReadingDay(day(3, 2), 1_200, goalMet = true, pages = 20),
            ReadingDay(day(3, 4), 60, goalMet = false),
        )
        val finished = listOf(
            FinishedItem(FinishKind.BOOK, "b1", "Old", 0, day(12, 30, 2025), 100),
            FinishedItem(FinishKind.BOOK, "b2", "New", 1, day(3, 2), 320),
            FinishedItem(FinishKind.CHAPTER, "7", "Series", 2, day(3, 2)),
        )
        val recap = YearRecaps.build(2026, days, emptyList(), finished, day(12, 31, 2025), day(3, 1), { _, _ -> emptyList() }, zone)

        assertEquals(1_860L, recap.totalSeconds)
        assertEquals(3, recap.daysRead)
        assertEquals(2, recap.goalDays)
        assertEquals(2, recap.longestStreak)
        assertEquals(listOf("New"), recap.booksFinished.map { it.title })
        assertEquals(1, recap.chaptersRead)
        assertEquals(32, recap.pagesTurned)
        assertNull("tracking began the year before", recap.countingSince)
        assertEquals(LocalDate.of(2026, 3, 1), recap.pagesSince)
        assertEquals(31, recap.monthMinutes[2])
        assertEquals(2, recap.bestMonth)
        assertEquals(LocalDate.of(2026, 3, 2) to 1_200L, recap.busiestDay)
    }

    @Test
    fun frozenDaysKeepAStreakWithoutAddingToIt() {
        val days = listOf(
            ReadingDay(day(5, 1), 400, goalMet = true),
            ReadingDay(day(5, 2), 0, frozen = true),
            ReadingDay(day(5, 3), 400, goalMet = true),
            ReadingDay(day(5, 5), 400, goalMet = true),
        ).associateBy { it.day }
        assertEquals(2, YearRecaps.longestStreak(days, YearRecaps.range(2026)))
    }

    @Test
    fun favouritesGenresAndTimeOfDayAreWeightedByReadingTime() {
        val d = day(6, 1)
        val sessions = listOf(
            session(ReadingKind.BOOK, "b1", "Short Book", d, 8, 600),
            session(ReadingKind.BOOK, "b2", "Long Book", d, 23, 3_000),
            session(ReadingKind.BOOK, "b2", "Long Book", d + 1, 23, 1_000),
            session(ReadingKind.MANGA, "5", "A Webtoon", d, 1, 900),
        )
        val genres = mapOf(
            "b1" to listOf("Poetry"),
            "b2" to listOf("Sisters -- Fiction", "Young women -- Fiction", "Love stories"),
            "5" to listOf("Romance"),
        )
        val recap = YearRecaps.build(2026, emptyList(), sessions, emptyList(), d, null, { _, id -> genres[id].orEmpty() }, zone)

        assertEquals("Long Book", recap.topBook?.title)
        assertEquals(4_000L, recap.topBook?.seconds)
        assertEquals("A Webtoon", recap.topSeries?.title)
        assertEquals(listOf("Fiction" to 4_000L, "Love stories" to 4_000L, "Romance" to 900L), recap.topGenres)
        assertEquals(TimeOfDay.NIGHT, recap.timeOfDay)
        assertEquals(4_900f / 5_500f, recap.timeOfDayShare, 0.001f)
        assertEquals(LocalDate.of(2026, 6, 1), recap.countingSince)
    }

    @Test
    fun emptyYearHasNothingToShow() {
        val recap = YearRecaps.build(2026, emptyList(), emptyList(), emptyList(), null, null, { _, _ -> emptyList() }, zone)
        assertTrue(recap.isEmpty)
        assertNull(recap.bestMonth)
        assertNull(recap.timeOfDay)
        assertTrue(recap.topGenres.isEmpty())
    }

    @Test
    fun subjectHeadingsBecomeReadableGenres() {
        assertEquals("Fiction", YearRecaps.normalizeGenre("England -- Fiction"))
        assertEquals("Science fiction", YearRecaps.normalizeGenre("Science fiction"))
        assertEquals("Whales", YearRecaps.normalizeGenre("Whales -- Juvenile literature."))
        assertEquals("Action", YearRecaps.normalizeGenre("Action"))
        assertEquals("", YearRecaps.normalizeGenre("  "))
    }
}
