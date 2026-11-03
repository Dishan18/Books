package io.github.dishan18.books.rewards.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stored by name: new kinds go at the end. Slides count toward streaks and reading time like a
 * book, but are kept apart so Year in Books can leave them out.
 */
enum class ReadingKind { BOOK, MANGA, SLIDES }

/** One stretch of reading in one book or manga series. */
@Entity(tableName = "reading_sessions", indices = [Index("day"), Index(value = ["kind", "itemId"])])
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ReadingKind,
    /** Book id, or manga series id as text. */
    val itemId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    /** Seconds the reader was actually being used (idle gaps are capped). */
    val activeSeconds: Long,
    /** Local calendar day ([java.time.LocalDate.toEpochDay]) the session started on. */
    val day: Long,
    /** Pages turned forward during the session (counted since v3). */
    @ColumnInfo(defaultValue = "0") val pages: Int = 0,
)

/** Reading totals per local calendar day; the source of truth for goals and streaks. */
@Entity(tableName = "reading_days")
data class ReadingDay(
    @PrimaryKey val day: Long,
    val activeSeconds: Long = 0,
    val goalMet: Boolean = false,
    /** Kept the streak alive without reading: a streak freeze or vacation mode. */
    val frozen: Boolean = false,
    val lateNight: Boolean = false,
    val earlyMorning: Boolean = false,
    /** Pages turned forward that day (counted since v3). */
    @ColumnInfo(defaultValue = "0") val pages: Int = 0,
)

@Entity(tableName = "earned_badges")
data class EarnedBadge(
    @PrimaryKey val badgeId: String,
    val earnedAt: Long,
    val seen: Boolean = false,
)

enum class FinishKind { BOOK, CHAPTER, SERIES }

/** Something finished in a reader, recorded once so badges and XP can't be farmed by re-reading. */
@Entity(tableName = "finished_items", primaryKeys = ["kind", "itemId"], indices = [Index("day")])
data class FinishedItem(
    val kind: FinishKind,
    val itemId: String,
    val title: String,
    val finishedAt: Long,
    val day: Long,
    /** Page count for books (for the 500-page badge), 0 when unknown. */
    val pages: Int = 0,
)
