package io.github.dishan18.books.rewards

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RewardsPrefs(
    /** Streaks, badges, reminders and celebrations. Reading time is still recorded for stats when off. */
    val enabled: Boolean = true,
    val goalMinutes: Int = 5,
    val reminderEnabled: Boolean = false,
    /** Minutes after midnight. */
    val reminderAt: Int = 20 * 60,
    val freezes: Int = 0,
    /** Streak length at which the last freeze was awarded, so each 7 days gives exactly one. */
    val freezeAwardedAt: Int = 0,
    val everUsedFreeze: Boolean = false,
    /** Local epoch day vacation mode started, or null. */
    val vacationSince: Long? = null,
    val lastLevel: Int = 1,
) {
    val goalSeconds get() = goalMinutes * 60L
}

class RewardsSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rewards", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<RewardsPrefs> = _state.asStateFlow()

    val value get() = _state.value

    fun update(change: (RewardsPrefs) -> RewardsPrefs) {
        val next = change(_state.value).let {
            it.copy(goalMinutes = it.goalMinutes.coerceIn(1, 120), freezes = it.freezes.coerceIn(0, MAX_FREEZES))
        }
        prefs.edit {
            putBoolean("enabled", next.enabled)
            putInt("goalMinutes", next.goalMinutes)
            putBoolean("reminderEnabled", next.reminderEnabled)
            putInt("reminderAt", next.reminderAt)
            putInt("freezes", next.freezes)
            putInt("freezeAwardedAt", next.freezeAwardedAt)
            putBoolean("everUsedFreeze", next.everUsedFreeze)
            if (next.vacationSince == null) remove("vacationSince") else putLong("vacationSince", next.vacationSince)
            putInt("lastLevel", next.lastLevel)
        }
        _state.value = next
    }

    private fun read() = RewardsPrefs(
        enabled = prefs.getBoolean("enabled", true),
        goalMinutes = prefs.getInt("goalMinutes", 5),
        reminderEnabled = prefs.getBoolean("reminderEnabled", false),
        reminderAt = prefs.getInt("reminderAt", 20 * 60),
        freezes = prefs.getInt("freezes", 0),
        freezeAwardedAt = prefs.getInt("freezeAwardedAt", 0),
        everUsedFreeze = prefs.getBoolean("everUsedFreeze", false),
        vacationSince = if (prefs.contains("vacationSince")) prefs.getLong("vacationSince", 0) else null,
        lastLevel = prefs.getInt("lastLevel", 1),
    )

    companion object {
        const val MAX_FREEZES = 2
    }
}
