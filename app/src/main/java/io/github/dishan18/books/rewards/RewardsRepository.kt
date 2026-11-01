package io.github.dishan18.books.rewards

import android.content.Context
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.rewards.data.EarnedBadge
import io.github.dishan18.books.rewards.data.FinishKind
import io.github.dishan18.books.rewards.data.FinishedItem
import io.github.dishan18.books.rewards.data.ReadingDay
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.rewards.data.ReadingSession
import io.github.dishan18.books.rewards.data.RewardsDao
import io.github.dishan18.books.rewards.engine.Badge
import io.github.dishan18.books.rewards.engine.BadgeCatalog
import io.github.dishan18.books.rewards.engine.DayFacts
import io.github.dishan18.books.rewards.engine.Levels
import io.github.dishan18.books.rewards.engine.Perks
import io.github.dishan18.books.rewards.engine.RewardStats
import io.github.dishan18.books.rewards.engine.Streaks
import io.github.dishan18.books.widget.ReadingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Moments the readers celebrate. */
sealed interface RewardEvent {
    data class GoalReached(val streak: Int) : RewardEvent
    data class BadgeEarned(val badge: Badge) : RewardEvent
    data class LevelUp(val level: Int, val perks: List<Perks.Perk>) : RewardEvent
    data class BookFinished(val title: String, val seconds: Long, val pages: Int, val confetti: Boolean) : RewardEvent
}

/** Everything the Progress screen shows. */
data class ProgressState(
    val prefs: RewardsPrefs = RewardsPrefs(),
    val today: Long = LocalDate.now().toEpochDay(),
    val todaySeconds: Long = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val todayDone: Boolean = false,
    val days: Map<Long, ReadingDay> = emptyMap(),
    val totalSeconds: Long = 0,
    val booksFinished: Int = 0,
    val booksThisYear: Int = 0,
    val chaptersRead: Int = 0,
    val xp: Int = 0,
    val level: Int = 1,
    val levelProgress: Float = 0f,
    val earned: List<EarnedBadge> = emptyList(),
) {
    /** Level used for unlocks: the highest ever reached, so rewards stay unlocked for good. */
    val unlockedLevel: Int get() = maxOf(level, prefs.lastLevel)
}

/**
 * Reading time, daily goals, streaks, badges and levels. Everything stays on the device.
 *
 * Readers call [resume] / [ping] / [pause]; time counts only while someone is interacting, with
 * gaps capped at [IDLE_CAP_MS] so a book left open doesn't keep counting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RewardsRepository(
    private val context: Context,
    private val dao: RewardsDao,
    val settings: RewardsSettings = RewardsSettings(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
    private val hour: () -> Int = { LocalTime.now().hour },
) {
    // One task at a time, in order, so a ping can never overtake the resume() that precedes it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val lock = Mutex()

    private val _events = MutableSharedFlow<RewardEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RewardEvent> = _events.asSharedFlow()

    // Active session state, guarded by [lock].
    private var identify: (() -> Pair<String, String>?)? = null
    private var kind: ReadingKind? = null
    private var sessionId: Long? = null
    private var sessionStartedAt = 0L
    private var sessionSecondsTotal = 0L
    private var lastPing = 0L
    private var pendingMs = 0L
    private var pendingPages = 0
    private var sessionPagesTotal = 0
    private var flushJob: Job? = null
    private var lastFreezeCheck = -1L

    // Read from any thread; only throttles how often ping() queues work.
    @Volatile private var lastPingQueued = 0L

    /** The current local day. Moves on at midnight so streaks and goals roll over while the app is open. */
    private val _today = MutableStateFlow(today())
    val currentDay: StateFlow<Long> = _today.asStateFlow()

    val progress: StateFlow<ProgressState> = combine(
        combine(settings.state, _today) { prefs, day -> prefs to day },
        dao.observeDays(),
        dao.observeTotalSeconds(),
        dao.observeBadges(),
        combine(
            dao.observeFinishedCount(FinishKind.BOOK),
            dao.observeFinishedCount(FinishKind.CHAPTER),
            dao.observeFinishedCount(FinishKind.SERIES),
            _today.map { startOfYear(it) }.distinctUntilChanged().flatMapLatest { dao.observeBooksFinishedSince(it) },
        ) { books, chapters, series, thisYear -> listOf(books, chapters, series, thisYear) },
    ) { (prefs, now), dayList, totalSeconds, badges, counts ->
        val days = dayList.associateBy { it.day }
        val streak = Streaks.compute(days, now)
        val stats = RewardStats(
            goalDays = days.values.count { it.goalMet },
            booksFinished = counts[0],
            chaptersRead = counts[1],
            seriesCompleted = counts[2],
            // Same source as evaluate(), so the level shown and the rewards unlocked always agree.
            totalMinutes = (days.values.sumOf { it.activeSeconds } / 60).toInt(),
        )
        val xp = Levels.xp(stats, badges.size)
        ProgressState(
            prefs = prefs,
            today = now,
            todaySeconds = days[now]?.activeSeconds ?: 0,
            currentStreak = streak.current,
            longestStreak = streak.longest,
            todayDone = streak.todayDone,
            days = days,
            totalSeconds = totalSeconds,
            booksFinished = counts[0],
            booksThisYear = counts[3],
            chaptersRead = counts[1],
            xp = xp,
            level = Levels.level(xp),
            levelProgress = Levels.progress(xp),
            earned = badges,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ProgressState())

    init {
        scope.launch { lock.withLock { applyFreezes() } }
        // Wake at each local midnight to start the new day. delay() holds no wake lock: if the phone
        // is asleep it fires late, and refreshDay() on resume covers that.
        scope.launch {
            while (isActive) {
                delay(millisUntilNextDay())
                refreshDay()
            }
        }
    }

    /** Level needed for [theme], or null if the reader may use it now. */
    fun lockedLevel(theme: ReaderTheme): Int? {
        if (!settings.value.enabled || Perks.isUnlocked(theme, unlockedLevel())) return null
        return Perks.unlockLevel(theme)
    }

    /** Highest level reached so far. Unlocks never go away once earned. */
    fun unlockedLevel(): Int = maxOf(progress.value.level, settings.value.lastLevel)

    /** Re-checks the date (after midnight, a time zone change, or the app coming back). */
    fun refreshDay() {
        scope.launch {
            lock.withLock {
                val day = today()
                if (day == _today.value) return@withLock
                _today.value = day
                applyFreezes()
                evaluate()
            }
        }
    }

    private fun millisUntilNextDay(): Long {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(clock()).atZone(zone)
        val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return Duration.between(now, next).toMillis().coerceAtLeast(0) + 1_000
    }

    // Tracking

    /** A reader came to the foreground. [identify] returns (item id, title) once the item is loaded. */
    fun resume(kind: ReadingKind, identify: () -> Pair<String, String>?) {
        val now = clock()
        refreshDay()
        scope.launch {
            lock.withLock {
                endSessionLocked()
                this@RewardsRepository.kind = kind
                this@RewardsRepository.identify = identify
                lastPing = now
            }
            startFlushing()
        }
    }

    /**
     * The reader is being used: a touch, page turn or scroll. Cheap to call on every touch event:
     * at most one update is queued every [PING_THROTTLE_MS], which loses nothing because time is
     * measured between updates and [pause] closes the last stretch.
     */
    fun ping() {
        val now = clock()
        if (now - lastPingQueued in 0 until PING_THROTTLE_MS) return
        lastPingQueued = now
        scope.launch { lock.withLock { accumulateLocked(now) } }
    }

    /** The reader moved forward a page (or a screen of a webtoon). Counted for stats only. */
    fun pageTurned() {
        scope.launch { lock.withLock { if (kind != null) pendingPages++ } }
    }

    /** The reader left the foreground: count the last stretch and save. */
    fun pause() {
        val now = clock()
        scope.launch {
            lock.withLock {
                accumulateLocked(now)
                flushLocked()
                endSessionLocked()
            }
        }
    }

    private fun accumulateLocked(now: Long) {
        if (kind == null) return
        if (lastPing > 0) pendingMs += (now - lastPing).coerceIn(0, IDLE_CAP_MS)
        lastPing = now
    }

    private fun endSessionLocked() {
        flushJob?.cancel()
        flushJob = null
        kind = null
        identify = null
        sessionId = null
        lastPing = 0
        pendingMs = 0
        pendingPages = 0
        sessionPagesTotal = 0
    }

    private fun startFlushing() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                lock.withLock { flushLocked() }
            }
        }
    }

    /** Writes pending reading time to the session and today's totals, then checks for rewards. */
    private suspend fun flushLocked() {
        val currentKind = kind ?: return
        val seconds = pendingMs / 1000
        val pages = pendingPages
        if (seconds <= 0 && pages <= 0) return
        val (itemId, title) = identify?.invoke() ?: return
        pendingMs -= seconds * 1000
        pendingPages = 0
        val now = clock()
        val day = today()
        val id = sessionId
        if (id == null) {
            sessionStartedAt = now - seconds * 1000
            sessionSecondsTotal = seconds
            sessionPagesTotal = pages
            sessionId = dao.insertSession(
                ReadingSession(
                    kind = currentKind, itemId = itemId, title = title, startedAt = sessionStartedAt, endedAt = now,
                    activeSeconds = seconds, day = day, pages = pages,
                ),
            )
        } else {
            sessionSecondsTotal += seconds
            sessionPagesTotal += pages
            dao.updateSession(ReadingSession(id, currentKind, itemId, title, sessionStartedAt, now, sessionSecondsTotal, day, sessionPagesTotal))
        }
        if (lastFreezeCheck != day) applyFreezes()
        val prefs = settings.value
        val currentHour = hour()
        val (before, after) = dao.addToDay(
            day, seconds, pages, prefs.goalSeconds, lateNight = currentHour in 0..3, earlyMorning = currentHour in 5..6,
        )
        if (before?.goalMet != true && after.goalMet && prefs.enabled) {
            val streak = Streaks.compute(dao.allDays().associateBy { it.day }, day).current
            _events.tryEmit(RewardEvent.GoalReached(streak))
        }
        evaluate()
    }

    // Finishing

    /** A book reached its last page. Celebrated once per book. */
    fun onBookFinished(bookId: String, title: String, pages: Int) {
        scope.launch {
            lock.withLock {
                flushLocked()
                val inserted = dao.insertFinished(FinishedItem(FinishKind.BOOK, bookId, title, clock(), today(), pages)) != -1L
                if (!inserted) return@withLock
                if (settings.value.enabled) {
                    val confetti = unlockedLevel() >= Perks.all.first().level
                    _events.tryEmit(RewardEvent.BookFinished(title, dao.secondsFor(ReadingKind.BOOK, bookId), pages, confetti))
                }
                evaluate()
            }
        }
    }

    /** Manga chapters finished in the reader; [seriesCompleted] when that finished a completed series. */
    fun onChaptersRead(seriesId: Long, seriesTitle: String, chapterIds: List<Long>, seriesCompleted: Boolean) {
        scope.launch {
            lock.withLock {
                val now = clock()
                val day = today()
                chapterIds.forEach { dao.insertFinished(FinishedItem(FinishKind.CHAPTER, it.toString(), seriesTitle, now, day)) }
                if (seriesCompleted) dao.insertFinished(FinishedItem(FinishKind.SERIES, seriesId.toString(), seriesTitle, now, day))
                evaluate()
            }
        }
    }

    // Settings

    fun setGoalMinutes(minutes: Int) {
        settings.update { it.copy(goalMinutes = minutes) }
        ReadingWidget.refresh(context)
        // Days already past keep their result; today re-checks against the new goal.
        scope.launch {
            lock.withLock {
                val day = dao.getDay(today()) ?: return@withLock
                if (!day.goalMet && day.activeSeconds >= minutes * 60L) {
                    dao.putDay(day.copy(goalMet = true))
                    evaluate()
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(enabled = enabled) }
        ReadingWidget.refresh(context)
    }

    fun setVacation(on: Boolean) {
        scope.launch {
            lock.withLock {
                if (on) {
                    settings.update { it.copy(vacationSince = today()) }
                } else {
                    applyFreezes()
                    settings.update { it.copy(vacationSince = null) }
                }
                ReadingWidget.refresh(context)
            }
        }
    }

    fun markBadgesSeen() {
        scope.launch { dao.markBadgesSeen() }
    }

    // Rules

    /**
     * Vacation days are frozen for free; otherwise a gap is covered by streak freezes if there are
     * enough of them. Runs at startup and on the first save of each day.
     */
    private suspend fun applyFreezes() {
        val day = today()
        lastFreezeCheck = day
        val prefs = settings.value
        val days = dao.allDays().associateBy { it.day }.toMutableMap()
        prefs.vacationSince?.let { since ->
            for (d in since until day) {
                val record = days[d]
                if (record?.goalMet != true && record?.frozen != true) {
                    val frozen = (record ?: ReadingDay(d)).copy(frozen = true)
                    dao.putDay(frozen)
                    days[d] = frozen
                }
            }
        }
        if (!prefs.enabled) return
        val toFreeze = Streaks.daysToFreeze(days, day, prefs.freezes)
        if (toFreeze.isNotEmpty()) {
            toFreeze.forEach { d -> dao.putDay((days[d] ?: ReadingDay(d)).copy(frozen = true)) }
            settings.update { it.copy(freezes = it.freezes - toFreeze.size, everUsedFreeze = true) }
        }
    }

    /** Awards freezes, badges and level-ups that the current data qualifies for. */
    private suspend fun evaluate() {
        val prefs = settings.value
        val day = today()
        val days = dao.allDays().associateBy { it.day }
        val streak = Streaks.compute(days, day)

        // One freeze for every 7 days of streak, holding at most two.
        val milestone = streak.current / 7 * 7
        if (milestone >= 7 && milestone > prefs.freezeAwardedAt) {
            settings.update { it.copy(freezes = it.freezes + 1, freezeAwardedAt = milestone) }
        } else if (streak.current < prefs.freezeAwardedAt) {
            settings.update { it.copy(freezeAwardedAt = milestone) }
        }

        val stats = RewardStats(
            realSessions = dao.countRealSessions(),
            goalDays = days.values.count { it.goalMet },
            currentStreak = streak.current,
            longestStreak = streak.longest,
            booksFinished = dao.countFinished(FinishKind.BOOK),
            maxBookPages = dao.maxFinishedBookPages() ?: 0,
            chaptersRead = dao.countFinished(FinishKind.CHAPTER),
            maxChaptersInDay = dao.busiestDay(FinishKind.CHAPTER)?.count ?: 0,
            seriesCompleted = dao.countFinished(FinishKind.SERIES),
            maxDayMinutes = ((days.values.maxOfOrNull { it.activeSeconds } ?: 0) / 60).toInt(),
            totalMinutes = (days.values.sumOf { it.activeSeconds } / 60).toInt(),
            lateNight = days.values.any { it.lateNight },
            earlyMorning = days.values.any { it.earlyMorning },
            weekendWarrior = DayFacts.weekendWarrior(days),
            perfectWeek = DayFacts.perfectWeek(days),
            comeback = DayFacts.comeback(days),
            freezeUsed = settings.value.everUsedFreeze,
        )
        val earnedIds = dao.earnedBadgeIds().toSet()
        val fresh = BadgeCatalog.newlyEarned(stats, earnedIds)
        if (fresh.isNotEmpty()) {
            val now = clock()
            dao.insertBadges(fresh.map { EarnedBadge(it.id, now) })
            if (prefs.enabled) fresh.forEach { _events.tryEmit(RewardEvent.BadgeEarned(it)) }
        }

        val level = Levels.level(Levels.xp(stats, earnedIds.size + fresh.size))
        if (level > prefs.lastLevel) {
            settings.update { it.copy(lastLevel = level) }
            if (prefs.enabled) _events.tryEmit(RewardEvent.LevelUp(level, Perks.newlyUnlocked(prefs.lastLevel, level)))
        }
    }

    private fun startOfYear(day: Long): Long =
        LocalDate.ofEpochDay(day).withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        const val PING_THROTTLE_MS = 2_000L
        /** Longest gap between interactions that still counts as reading (a dense page). */
        const val IDLE_CAP_MS = 2 * 60_000L
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}
