package io.github.dishan18.books.rewards.engine

import io.github.dishan18.books.rewards.data.ReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RewardEnginesTest {

    private val today = LocalDate.of(2026, 11, 4).toEpochDay() // a Wednesday

    private fun days(vararg entries: Pair<Int, Char>): Map<Long, ReadingDay> = entries.associate { (offset, kind) ->
        val day = today + offset
        day to when (kind) {
            'G' -> ReadingDay(day, activeSeconds = 400, goalMet = true)
            'F' -> ReadingDay(day, frozen = true)
            'r' -> ReadingDay(day, activeSeconds = 90) // read a little, goal not met
            else -> error("unknown")
        }
    }

    @Test
    fun streakCountsBackFromYesterdayUntilTodayIsDone() {
        val history = days(-3 to 'G', -2 to 'G', -1 to 'G')
        assertEquals(StreakInfo(current = 3, longest = 3, todayDone = false), Streaks.compute(history, today))
        val withToday = history + days(0 to 'G')
        assertEquals(4, Streaks.compute(withToday, today).current)
    }

    @Test
    fun missedDayBreaksTheStreakButFrozenDaysDoNot() {
        assertEquals(1, Streaks.compute(days(-3 to 'G', -2 to 'r', -1 to 'G'), today).current)
        val frozen = Streaks.compute(days(-4 to 'G', -3 to 'G', -2 to 'F', -1 to 'G'), today)
        assertEquals("frozen day keeps the chain but doesn't count", 3, frozen.current)
    }

    @Test
    fun longestStreakSurvivesALaterBreak() {
        val info = Streaks.compute(days(-10 to 'G', -9 to 'G', -8 to 'G', -7 to 'G', -3 to 'G', -1 to 'G'), today)
        assertEquals(1, info.current)
        assertEquals(4, info.longest)
    }

    @Test
    fun freezesCoverAGapOnlyIfTheyAreEnough() {
        val history = days(-5 to 'G', -4 to 'G', -3 to 'G')
        assertEquals(listOf(today - 1, today - 2), Streaks.daysToFreeze(history, today, freezes = 2))
        assertEquals(emptyList<Long>(), Streaks.daysToFreeze(history, today, freezes = 1))
        assertEquals("nothing to save without a streak", emptyList<Long>(), Streaks.daysToFreeze(emptyMap(), today, 2))
        assertEquals("no gap, no freeze", emptyList<Long>(), Streaks.daysToFreeze(days(-1 to 'G'), today, 2))
    }

    @Test
    fun badgesAreAwardedOnceTheirConditionHolds() {
        val stats = RewardStats(realSessions = 3, goalDays = 1, longestStreak = 7, booksFinished = 1, maxBookPages = 640)
        val earned = BadgeCatalog.newlyEarned(stats, emptySet()).map { it.id }.toSet()
        assertEquals(setOf("first_steps", "goal_getter", "streak_3", "streak_7", "book_1", "doorstopper"), earned)
        assertTrue("already earned badges aren't repeated", BadgeCatalog.newlyEarned(stats, earned).isEmpty())
    }

    @Test
    fun dayFactsSpotWeekendsPerfectWeeksAndComebacks() {
        val monday = LocalDate.of(2026, 11, 2).toEpochDay()
        val week = (0L..6L).associate { monday + it to ReadingDay(monday + it, 400, goalMet = true) }
        assertTrue(DayFacts.perfectWeek(week))
        assertTrue(DayFacts.weekendWarrior(week))
        assertFalse(DayFacts.perfectWeek(week - (monday + 3)))

        val gap = mapOf(
            monday to ReadingDay(monday, 400, goalMet = true),
            monday + 10 to ReadingDay(monday + 10, 400, goalMet = true),
        )
        assertTrue(DayFacts.comeback(gap))
        val vacation = gap + (monday + 1..monday + 9).associateWith { ReadingDay(it, frozen = true) }
        assertFalse("a vacation isn't a comeback", DayFacts.comeback(vacation))
    }

    @Test
    fun levelsGrowQuadratically() {
        assertEquals(1, Levels.level(0))
        assertEquals(2, Levels.level(50))
        assertEquals(3, Levels.level(200))
        assertEquals(5, Levels.level(800))
        assertEquals(0.5f, Levels.progress(125), 0.001f)
        val xp = Levels.xp(RewardStats(totalMinutes = 30, chaptersRead = 2, booksFinished = 1, goalDays = 3), badgeCount = 2)
        assertEquals(30 + 20 + 50 + 60 + 50, xp)
    }

    @Test
    fun perksUnlockAtTheirLevels() {
        assertFalse(Perks.isUnlocked(io.github.dishan18.books.data.ReaderTheme.MIDNIGHT, level = 2))
        assertTrue(Perks.isUnlocked(io.github.dishan18.books.data.ReaderTheme.MIDNIGHT, level = 3))
        assertTrue("built-in themes are always available", Perks.isUnlocked(io.github.dishan18.books.data.ReaderTheme.SEPIA, level = 1))
        assertEquals(listOf(3, 5), Perks.newlyUnlocked(2, 5).filter { it.theme != null }.map { it.level })
    }
}
