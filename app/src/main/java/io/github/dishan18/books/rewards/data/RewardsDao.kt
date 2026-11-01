package io.github.dishan18.books.rewards.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class DayCount(val day: Long, val count: Int)

@Dao
interface RewardsDao {
    // Sessions

    @Insert
    suspend fun insertSession(session: ReadingSession): Long

    @Update
    suspend fun updateSession(session: ReadingSession)

    @Query("SELECT COALESCE(SUM(activeSeconds), 0) FROM reading_sessions")
    fun observeTotalSeconds(): Flow<Long>

    @Query("SELECT COALESCE(SUM(activeSeconds), 0) FROM reading_sessions WHERE kind = :kind AND itemId = :itemId")
    suspend fun secondsFor(kind: ReadingKind, itemId: String): Long

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE activeSeconds >= 60")
    suspend fun countRealSessions(): Int

    // Days

    @Query("SELECT * FROM reading_days WHERE day = :day")
    suspend fun getDay(day: Long): ReadingDay?

    @Query("SELECT * FROM reading_days WHERE day = :day")
    fun observeDay(day: Long): Flow<ReadingDay?>

    @Query("SELECT * FROM reading_days ORDER BY day")
    suspend fun allDays(): List<ReadingDay>

    @Query("SELECT * FROM reading_days ORDER BY day")
    fun observeDays(): Flow<List<ReadingDay>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDay(day: ReadingDay)

    /** Adds reading time to [day], returning the day before and after the change. */
    @Transaction
    suspend fun addToDay(
        day: Long,
        seconds: Long,
        pages: Int,
        goalSeconds: Long,
        lateNight: Boolean,
        earlyMorning: Boolean,
    ): Pair<ReadingDay?, ReadingDay> {
        val before = getDay(day)
        val base = before ?: ReadingDay(day)
        val total = base.activeSeconds + seconds
        val after = base.copy(
            activeSeconds = total,
            pages = base.pages + pages,
            goalMet = base.goalMet || total >= goalSeconds,
            lateNight = base.lateNight || lateNight,
            earlyMorning = base.earlyMorning || earlyMorning,
        )
        putDay(after)
        return before to after
    }

    // Badges

    @Query("SELECT * FROM earned_badges ORDER BY earnedAt DESC")
    fun observeBadges(): Flow<List<EarnedBadge>>

    @Query("SELECT badgeId FROM earned_badges")
    suspend fun earnedBadgeIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBadges(badges: List<EarnedBadge>): List<Long>

    @Query("UPDATE earned_badges SET seen = 1 WHERE seen = 0")
    suspend fun markBadgesSeen()

    // Finished items

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFinished(item: FinishedItem): Long

    @Query("SELECT COUNT(*) FROM finished_items WHERE kind = :kind")
    suspend fun countFinished(kind: FinishKind): Int

    @Query("SELECT COUNT(*) FROM finished_items WHERE kind = :kind")
    fun observeFinishedCount(kind: FinishKind): Flow<Int>

    @Query("SELECT COUNT(*) FROM finished_items WHERE kind = 'BOOK' AND finishedAt >= :since")
    fun observeBooksFinishedSince(since: Long): Flow<Int>

    @Query("SELECT MAX(pages) FROM finished_items WHERE kind = 'BOOK'")
    suspend fun maxFinishedBookPages(): Int?

    @Query("SELECT day, COUNT(*) AS count FROM finished_items WHERE kind = :kind GROUP BY day ORDER BY count DESC LIMIT 1")
    suspend fun busiestDay(kind: FinishKind): DayCount?

    // Year in Books (local epoch days, inclusive)

    @Query("SELECT * FROM reading_days WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun daysBetween(from: Long, to: Long): List<ReadingDay>

    @Query("SELECT * FROM reading_sessions WHERE day BETWEEN :from AND :to")
    suspend fun sessionsBetween(from: Long, to: Long): List<ReadingSession>

    @Query("SELECT * FROM finished_items WHERE day BETWEEN :from AND :to ORDER BY finishedAt")
    suspend fun finishedBetween(from: Long, to: Long): List<FinishedItem>

    @Query("SELECT DISTINCT day FROM reading_days WHERE activeSeconds > 0 UNION SELECT DISTINCT day FROM finished_items")
    suspend fun activeDays(): List<Long>

    @Query("SELECT MIN(day) FROM reading_days WHERE pages > 0")
    suspend fun firstPagesDay(): Long?
}
