package io.github.dishan18.books.rewards.engine

import io.github.dishan18.books.data.ReaderTheme
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * XP is derived from what's stored (minutes, finishes, goals, badges) rather than kept as a running
 * total, so it can never drift or be double-counted.
 */
object Levels {
    fun xp(stats: RewardStats, badgeCount: Int): Int =
        stats.totalMinutes +
            stats.chaptersRead * 10 +
            stats.booksFinished * 50 +
            stats.seriesCompleted * 100 +
            stats.goalDays * 20 +
            badgeCount * 25

    /** Level 1 at 0 XP; level n needs 50·(n−1)² XP (50, 200, 450, 800 …). */
    fun level(xp: Int): Int = floor(sqrt(xp.coerceAtLeast(0) / 50.0)).toInt() + 1

    fun xpForLevel(level: Int): Int = 50 * (level - 1) * (level - 1)

    /** Progress from the current level to the next, 0..1. */
    fun progress(xp: Int): Float {
        val level = level(xp)
        val start = xpForLevel(level)
        val end = xpForLevel(level + 1)
        return ((xp - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

/** Cosmetic rewards unlocked by level. Rewards never gate features. */
object Perks {
    data class Perk(val level: Int, val title: String, val theme: ReaderTheme? = null)

    val all = listOf(
        Perk(2, "Confetti when you finish a book"),
        Perk(3, "Midnight reading theme", ReaderTheme.MIDNIGHT),
        Perk(5, "Forest reading theme", ReaderTheme.FOREST),
        Perk(8, "True black (AMOLED) theme", ReaderTheme.AMOLED),
        Perk(12, "Golden streak flame"),
    )

    fun unlockLevel(theme: ReaderTheme): Int = all.firstOrNull { it.theme == theme }?.level ?: 1

    fun isUnlocked(theme: ReaderTheme, level: Int) = level >= unlockLevel(theme)

    fun newlyUnlocked(oldLevel: Int, newLevel: Int): List<Perk> = all.filter { it.level in (oldLevel + 1)..newLevel }
}
