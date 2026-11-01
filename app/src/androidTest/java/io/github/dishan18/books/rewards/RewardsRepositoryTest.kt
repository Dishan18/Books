package io.github.dishan18.books.rewards

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.rewards.data.FinishKind
import io.github.dishan18.books.rewards.data.ReadingDay
import io.github.dishan18.books.rewards.data.ReadingKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class RewardsRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: BooksDatabase
    private var now = 1_800_000_000_000L
    private val day = 20_000L
    private val events = Collections.synchronizedList(mutableListOf<RewardEvent>())
    private val collector = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("rewards", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, BooksDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        collector.cancel()
        db.close()
        context.getSharedPreferences("rewards", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private var currentDay = day

    private fun repository(today: Long = day): RewardsRepository {
        currentDay = today
        return RewardsRepository(context, db.rewards(), RewardsSettings(context), clock = { now }, today = { currentDay }, hour = { 21 })
            .also { repo -> collector.launch { repo.events.collect { events += it } } }
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(20)
    }

    /** Simulates reading with a touch every [gapSeconds] for [minutes]. */
    private fun RewardsRepository.read(minutes: Int, gapSeconds: Int = 60) {
        resume(ReadingKind.BOOK) { "book-1" to "A Book" }
        repeat(minutes * 60 / gapSeconds) {
            now += gapSeconds * 1000L
            ping()
        }
        pause()
    }

    @Test
    fun readingTimeReachesTheGoalAndEarnsFirstBadges() = runBlocking {
        val repo = repository()
        delay(100) // let the collector subscribe
        repo.read(minutes = 6)

        waitFor { db.rewards().getDay(day)?.goalMet == true }
        assertEquals(360L, db.rewards().getDay(day)!!.activeSeconds)
        waitFor { db.rewards().earnedBadgeIds().containsAll(listOf("first_steps", "goal_getter")) }
        waitFor { events.any { it is RewardEvent.GoalReached } }
        assertEquals(1, (events.first { it is RewardEvent.GoalReached } as RewardEvent.GoalReached).streak)
    }

    @Test
    fun longIdleGapsCountAsAtMostTwoMinutes() = runBlocking {
        val repo = repository()
        repo.resume(ReadingKind.MANGA) { "1" to "A Series" }
        now += 10 * 60_000L // ten minutes without touching
        repo.ping()
        repo.pause()

        waitFor { db.rewards().getDay(day) != null }
        assertEquals(120L, db.rewards().getDay(day)!!.activeSeconds)
        assertEquals(false, db.rewards().getDay(day)!!.goalMet)
    }

    @Test
    fun streakFreezeCoversAMissedDay() = runBlocking {
        listOf(day - 4, day - 3, day - 2).forEach { db.rewards().putDay(ReadingDay(it, 400, goalMet = true)) }
        RewardsSettings(context).update { it.copy(freezes = 1) }

        val repo = repository()
        waitFor { db.rewards().getDay(day - 1)?.frozen == true }
        waitFor { repo.settings.value.freezes == 0 }
        assertTrue(repo.settings.value.everUsedFreeze)
        waitFor { repo.progress.value.currentStreak == 3 }
    }

    @Test
    fun finishingABookIsCelebratedOnce() = runBlocking {
        val repo = repository()
        delay(100)
        repo.onBookFinished("book-1", "A Book", pages = 640)
        repo.onBookFinished("book-1", "A Book", pages = 640)

        waitFor { db.rewards().earnedBadgeIds().containsAll(listOf("book_1", "doorstopper")) }
        assertEquals(1, db.rewards().countFinished(FinishKind.BOOK))
        delay(200)
        assertEquals(1, events.count { it is RewardEvent.BookFinished })
    }

    @Test
    fun aNewDayRollsOverWithoutRestarting() = runBlocking {
        val repo = repository()
        repo.read(minutes = 6)
        waitFor { repo.progress.value.todayDone && repo.progress.value.currentStreak == 1 }

        currentDay = day + 1
        repo.refreshDay()
        waitFor { repo.progress.value.today == day + 1 }
        assertEquals("yesterday's reading still counts", 1, repo.progress.value.currentStreak)
        assertEquals(false, repo.progress.value.todayDone)
        assertEquals(0L, repo.progress.value.todaySeconds)

        repo.read(minutes = 5)
        waitFor { repo.progress.value.currentStreak == 2 }
    }

    @Test
    fun forwardPageTurnsAreCountedPerDayAndSession() = runBlocking {
        val repo = repository()
        repo.resume(ReadingKind.BOOK) { "book-1" to "A Book" }
        repeat(3) {
            now += 20_000
            repo.ping()
            repo.pageTurned()
        }
        repo.pause()
        waitFor { db.rewards().getDay(day)?.pages == 3 }
        assertEquals(60L, db.rewards().getDay(day)!!.activeSeconds)
        assertEquals(listOf(3), db.rewards().sessionsBetween(day, day).map { it.pages })

        repo.pageTurned() // No reader open: nothing to count.
        delay(200)
        assertEquals(3, db.rewards().getDay(day)!!.pages)
    }

    @Test
    fun rapidTouchesAreThrottledWithoutLosingTime() = runBlocking {
        val repo = repository()
        repo.resume(ReadingKind.MANGA) { "1" to "A Series" }
        // A scroll: a touch event every 10 ms for 90 seconds.
        repeat(9_000) {
            now += 10
            repo.ping()
        }
        repo.pause()
        waitFor { db.rewards().getDay(day) != null }
        assertEquals(90L, db.rewards().getDay(day)!!.activeSeconds)
    }

    @Test
    fun unlocksStayOnceALevelIsReached() = runBlocking {
        RewardsSettings(context).update { it.copy(lastLevel = 4) }
        val repo = repository()
        delay(100)
        assertEquals("Pink is a level 4 reward, already reached", null, repo.lockedLevel(io.github.dishan18.books.data.ReaderTheme.PINK))
        assertEquals(5, repo.lockedLevel(io.github.dishan18.books.data.ReaderTheme.FOREST))
    }

    @Test
    fun turningRewardsOffStillCountsTimeButStaysQuiet() = runBlocking {
        RewardsSettings(context).update { it.copy(enabled = false) }
        val repo = repository()
        delay(100)
        repo.read(minutes = 6)

        waitFor { db.rewards().getDay(day)?.goalMet == true }
        delay(200)
        assertTrue("no celebrations when rewards are off", events.isEmpty())
    }
}
