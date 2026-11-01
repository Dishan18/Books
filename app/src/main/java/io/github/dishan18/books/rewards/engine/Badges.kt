package io.github.dishan18.books.rewards.engine

import io.github.dishan18.books.rewards.data.ReadingDay
import java.time.DayOfWeek
import java.time.LocalDate

/** Everything badges are judged on, gathered in one snapshot. */
data class RewardStats(
    val realSessions: Int = 0,
    val goalDays: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val booksFinished: Int = 0,
    val maxBookPages: Int = 0,
    val chaptersRead: Int = 0,
    val maxChaptersInDay: Int = 0,
    val seriesCompleted: Int = 0,
    val maxDayMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val lateNight: Boolean = false,
    val earlyMorning: Boolean = false,
    val weekendWarrior: Boolean = false,
    val perfectWeek: Boolean = false,
    val comeback: Boolean = false,
    val freezeUsed: Boolean = false,
)

data class Badge(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val hidden: Boolean = false,
    val earned: (RewardStats) -> Boolean,
)

object BadgeCatalog {
    val all: List<Badge> = listOf(
        Badge("first_steps", "👣", "First steps", "Read for a whole minute") { it.realSessions >= 1 },
        Badge("goal_getter", "🎯", "Goal getter", "Reach your daily goal for the first time") { it.goalDays >= 1 },
        Badge("streak_3", "🔥", "Warming up", "A 3-day streak") { it.longestStreak >= 3 },
        Badge("streak_7", "🔥", "On fire", "A 7-day streak") { it.longestStreak >= 7 },
        Badge("streak_30", "🌋", "Unstoppable", "A 30-day streak") { it.longestStreak >= 30 },
        Badge("streak_100", "☄️", "Legendary", "A 100-day streak") { it.longestStreak >= 100 },
        Badge("streak_365", "🌟", "A year of reading", "A 365-day streak") { it.longestStreak >= 365 },
        Badge("perfect_week", "🗓️", "Perfect week", "Reach your goal every day from Monday to Sunday") { it.perfectWeek },
        Badge("book_1", "📖", "The End", "Finish your first book") { it.booksFinished >= 1 },
        Badge("book_5", "📚", "Bookworm", "Finish 5 books") { it.booksFinished >= 5 },
        Badge("book_10", "🐛", "Page turner", "Finish 10 books") { it.booksFinished >= 10 },
        Badge("book_25", "🏛️", "Bibliophile", "Finish 25 books") { it.booksFinished >= 25 },
        Badge("book_50", "👑", "Library legend", "Finish 50 books") { it.booksFinished >= 50 },
        Badge("doorstopper", "🧱", "Doorstopper", "Finish a book of 500 pages or more") { it.maxBookPages >= 500 },
        Badge("hooked", "🪝", "Hooked", "Read 10 manga chapters in one day") { it.maxChaptersInDay >= 10 },
        Badge("chapters_100", "🏃", "Marathoner", "Read 100 manga chapters") { it.chaptersRead >= 100 },
        Badge("completionist", "🏁", "Completionist", "Read every chapter of a finished series") { it.seriesCompleted >= 1 },
        Badge("marathon", "⏱️", "Deep dive", "Read for an hour in one day") { it.maxDayMinutes >= 60 },
        Badge("hours_10", "⌛", "Ten hours", "Read for 10 hours in total") { it.totalMinutes >= 600 },
        Badge("hours_100", "💯", "Centurion", "Read for 100 hours in total") { it.totalMinutes >= 6_000 },
        Badge("night_owl", "🦉", "Night owl", "Read between midnight and 4 am") { it.lateNight },
        Badge("early_bird", "🐦", "Early bird", "Read between 5 and 7 am") { it.earlyMorning },
        Badge("weekend_warrior", "🛋️", "Weekend warrior", "Read on both days of a weekend") { it.weekendWarrior },
        Badge("comeback", "🌱", "Comeback", "Start a new streak after a break of a week or more", hidden = true) { it.comeback },
        Badge("ice", "🧊", "Saved by the ice", "A streak freeze kept your streak alive", hidden = true) { it.freezeUsed },
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Badge? = byId[id]

    /** Badges [stats] qualifies for that aren't in [earned] yet. */
    fun newlyEarned(stats: RewardStats, earned: Set<String>): List<Badge> =
        all.filter { it.id !in earned && it.earned(stats) }
}

/** Day-based facts for badges: weekends, perfect weeks, comebacks. Days are local epoch days. */
object DayFacts {
    fun weekendWarrior(days: Map<Long, ReadingDay>): Boolean = days.values.any { day ->
        day.activeSeconds >= 60 && LocalDate.ofEpochDay(day.day).dayOfWeek == DayOfWeek.SATURDAY &&
            (days[day.day + 1]?.activeSeconds ?: 0) >= 60
    }

    fun perfectWeek(days: Map<Long, ReadingDay>): Boolean = days.values.any { day ->
        LocalDate.ofEpochDay(day.day).dayOfWeek == DayOfWeek.MONDAY && (0L..6L).all { days[day.day + it]?.goalMet == true }
    }

    /** A goal day following at least 7 days without reading, after a previous goal day. */
    fun comeback(days: Map<Long, ReadingDay>): Boolean {
        val goalDays = days.values.filter { it.goalMet }.map { it.day }.sorted()
        return goalDays.zipWithNext().any { (a, b) ->
            b - a > 7 && (a + 1 until b).none { days[it]?.frozen == true }
        }
    }
}
