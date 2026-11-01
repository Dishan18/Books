package io.github.dishan18.books.rewards.engine

import io.github.dishan18.books.rewards.data.ReadingDay

data class StreakInfo(
    /** Days in a row the goal was met, ending today (if met) or yesterday. */
    val current: Int,
    val longest: Int,
    val todayDone: Boolean,
)

/**
 * Streak rules: a day counts when its goal was met. Frozen days (a streak freeze or vacation)
 * keep the chain unbroken but don't add to it. Today never breaks a streak until it's over.
 */
object Streaks {

    fun compute(days: Map<Long, ReadingDay>, today: Long): StreakInfo {
        val todayDone = days[today]?.goalMet == true
        var current = 0
        var day = if (todayDone) today else today - 1
        while (true) {
            val record = days[day] ?: break
            when {
                record.goalMet -> current++
                record.frozen -> Unit
                else -> break
            }
            day--
        }
        return StreakInfo(current = current, longest = maxOf(longest(days, today), current), todayDone = todayDone)
    }

    private fun longest(days: Map<Long, ReadingDay>, today: Long): Int {
        val first = days.keys.minOrNull() ?: return 0
        var best = 0
        var run = 0
        for (day in first..today) {
            val record = days[day]
            when {
                record?.goalMet == true -> run++
                record?.frozen == true -> Unit
                day == today -> Unit // Today isn't over yet.
                else -> run = 0
            }
            best = maxOf(best, run)
        }
        return best
    }

    /**
     * Days that should be frozen to save a streak: the unbroken run of missed days just before
     * today, if there's a streak to save and enough [freezes] to cover the whole gap.
     */
    fun daysToFreeze(days: Map<Long, ReadingDay>, today: Long, freezes: Int): List<Long> {
        if (freezes <= 0) return emptyList()
        val missed = mutableListOf<Long>()
        var day = today - 1
        while (true) {
            val record = days[day]
            if (record?.goalMet == true || record?.frozen == true) break
            if (record == null && days.keys.none { it < day }) return emptyList() // Nothing before: no streak.
            missed += day
            day--
            if (missed.size > freezes) return emptyList()
        }
        if (missed.isEmpty()) return emptyList()
        val streakBefore = compute(days, day + 1).current
        return if (streakBefore > 0) missed else emptyList()
    }
}
