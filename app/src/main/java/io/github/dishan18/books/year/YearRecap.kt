package io.github.dishan18.books.year

import io.github.dishan18.books.rewards.data.FinishKind
import io.github.dishan18.books.rewards.data.FinishedItem
import io.github.dishan18.books.rewards.data.ReadingDay
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.rewards.data.ReadingSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TimeOfDay(val title: String, val hours: String) {
    MORNING("an early reader", "5 am to noon"),
    AFTERNOON("an afternoon reader", "noon to 5 pm"),
    EVENING("an evening reader", "5 pm to 10 pm"),
    NIGHT("a night owl", "10 pm to 5 am"),
}

/** Something read this year, with the time spent on it. */
data class ReadItem(val kind: ReadingKind, val itemId: String, val title: String, val seconds: Long)

data class FinishedBook(val itemId: String, val title: String, val pages: Int, val finishedAt: Long)

/** Everything the annual recap shows. All of it comes from what the app recorded; nothing is estimated. */
data class YearRecap(
    val year: Int,
    /** First day anything was recorded, when that falls inside this year (the recap covers less than a year). */
    val countingSince: LocalDate?,
    val totalSeconds: Long,
    val daysRead: Int,
    val goalDays: Int,
    val longestStreak: Int,
    val booksFinished: List<FinishedBook>,
    val chaptersRead: Int,
    val seriesCompleted: Int,
    val pagesTurned: Int,
    /** When page counting began, if that was during this year. */
    val pagesSince: LocalDate?,
    val topBook: ReadItem?,
    val topSeries: ReadItem?,
    /** Up to three genres or subjects, with the reading time behind each. */
    val topGenres: List<Pair<String, Long>>,
    /** Minutes read in each month, January first. */
    val monthMinutes: List<Int>,
    val busiestDay: Pair<LocalDate, Long>?,
    val timeOfDay: TimeOfDay?,
    /** Share of reading time in [timeOfDay], 0..1. */
    val timeOfDayShare: Float,
) {
    val isEmpty get() = totalSeconds == 0L && booksFinished.isEmpty() && chaptersRead == 0
    val bestMonth: Int? get() = monthMinutes.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
}

object YearRecaps {

    /** Local epoch-day range of [year], inclusive. */
    fun range(year: Int): LongRange = LocalDate.of(year, 1, 1).toEpochDay()..LocalDate.of(year, 12, 31).toEpochDay()

    /**
     * Builds the recap for [year] from that year's rows. [genresOf] returns the genres of a book
     * or series (empty when unknown); [firstRecordedDay] and [firstPagesDay] are across all years.
     */
    fun build(
        year: Int,
        days: List<ReadingDay>,
        sessions: List<ReadingSession>,
        finished: List<FinishedItem>,
        firstRecordedDay: Long?,
        firstPagesDay: Long?,
        genresOf: (ReadingKind, String) -> List<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): YearRecap {
        val range = range(year)
        val inYear = days.filter { it.day in range }
        val yearSessions = sessions.filter { it.day in range }
        val yearFinished = finished.filter { it.day in range }

        val items = yearSessions
            .groupBy { it.kind to it.itemId }
            .map { (key, list) -> ReadItem(key.first, key.second, list.maxBy { it.endedAt }.title, list.sumOf { it.activeSeconds }) }
            .filter { it.seconds > 0 }

        val months = MutableList(12) { 0L }
        inYear.forEach { months[LocalDate.ofEpochDay(it.day).monthValue - 1] += it.activeSeconds }

        val byTime = LongArray(TimeOfDay.entries.size)
        yearSessions.forEach { session ->
            val hour = Instant.ofEpochMilli(session.startedAt).atZone(zone).hour
            byTime[timeOfDay(hour).ordinal] += session.activeSeconds
        }
        val sessionSeconds = byTime.sum()
        val peak = byTime.indices.maxByOrNull { byTime[it] }?.takeIf { sessionSeconds > 0 }

        val genreSeconds = mutableMapOf<String, Long>()
        items.forEach { item ->
            genresOf(item.kind, item.itemId).map(::normalizeGenre).filter { it.isNotBlank() }.distinct().forEach { genre ->
                genreSeconds[genre] = (genreSeconds[genre] ?: 0) + item.seconds
            }
        }

        val first = range.first
        return YearRecap(
            year = year,
            countingSince = firstRecordedDay?.takeIf { it in range && it > first }?.let(LocalDate::ofEpochDay),
            totalSeconds = inYear.sumOf { it.activeSeconds },
            daysRead = inYear.count { it.activeSeconds > 0 },
            goalDays = inYear.count { it.goalMet },
            longestStreak = longestStreak(inYear.associateBy { it.day }, range),
            booksFinished = yearFinished.filter { it.kind == FinishKind.BOOK }.map { FinishedBook(it.itemId, it.title, it.pages, it.finishedAt) },
            chaptersRead = yearFinished.count { it.kind == FinishKind.CHAPTER },
            seriesCompleted = yearFinished.count { it.kind == FinishKind.SERIES },
            pagesTurned = inYear.sumOf { it.pages },
            pagesSince = firstPagesDay?.takeIf { it in range && it > first }?.let(LocalDate::ofEpochDay),
            // A favourite needs at least a minute of reading behind it.
            topBook = items.filter { it.kind == ReadingKind.BOOK && it.seconds >= 60 }.maxByOrNull { it.seconds },
            topSeries = items.filter { it.kind == ReadingKind.MANGA && it.seconds >= 60 }.maxByOrNull { it.seconds },
            topGenres = genreSeconds.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(3).map { it.key to it.value },
            monthMinutes = months.map { (it / 60).toInt() },
            busiestDay = inYear.maxByOrNull { it.activeSeconds }?.takeIf { it.activeSeconds > 0 }?.let { LocalDate.ofEpochDay(it.day) to it.activeSeconds },
            timeOfDay = peak?.let { TimeOfDay.entries[it] },
            timeOfDayShare = peak?.let { byTime[it].toFloat() / sessionSeconds } ?: 0f,
        )
    }

    fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
        in 5..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..21 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }

    /** Longest run of goal days inside [range]; frozen days keep a run going without adding to it. */
    fun longestStreak(days: Map<Long, ReadingDay>, range: LongRange): Int {
        var best = 0
        var run = 0
        for (day in range) {
            val record = days[day]
            when {
                record?.goalMet == true -> run++
                record?.frozen == true -> Unit
                else -> run = 0
            }
            best = maxOf(best, run)
        }
        return best
    }

    private val forms = setOf("fiction", "poetry", "drama", "juvenile fiction", "humor", "biography", "history", "comic books, strips, etc.")

    /**
     * Library subject headings ("Sisters -- Fiction") become a readable genre: the form when the
     * heading ends in one ("Fiction"), otherwise its main topic. Plain genres pass through.
     */
    fun normalizeGenre(raw: String): String {
        val parts = raw.split(" -- ", "--").map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val last = parts.last()
        val chosen = if (parts.size > 1 && last.lowercase() in forms) last else parts.first()
        return chosen.replaceFirstChar { it.uppercase() }
    }
}
