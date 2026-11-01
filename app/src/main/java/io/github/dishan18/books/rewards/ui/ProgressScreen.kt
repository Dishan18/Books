package io.github.dishan18.books.rewards.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.R
import io.github.dishan18.books.app
import io.github.dishan18.books.rewards.AppIcons
import io.github.dishan18.books.rewards.ProgressState
import io.github.dishan18.books.rewards.ReadingReminderWorker
import io.github.dishan18.books.rewards.engine.AppIcon
import io.github.dishan18.books.rewards.engine.BadgeCatalog
import io.github.dishan18.books.rewards.engine.Levels
import io.github.dishan18.books.rewards.engine.Perks
import io.github.dishan18.books.year.YearInBooksActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

private val FlameOrange = Color(0xFFFF7A1A)
private val FlameGold = Color(0xFFFFC233)
private val IceBlue = Color(0xFF7CC4FF)

@Composable
fun ProgressScreen() {
    val context = LocalContext.current
    val rewards = context.app.rewards
    val state by rewards.progress.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { rewards.markBadgesSeen() }
    // Coming back after midnight shows the new day straight away.
    LifecycleResumeEffect(Unit) {
        rewards.refreshDay()
        onPauseOrDispose { }
    }
    LaunchedEffect(prefs.enabled, prefs.reminderEnabled, prefs.reminderAt) { ReadingReminderWorker.sync(context, prefs) }

    Column(
        // Padding outside the scroll keeps content from sliding under the status bar.
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Progress", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 24.dp))

        if (prefs.enabled) {
            StreakCard(state)
            WeekStrip(state)
            LevelCard(state)
        }
        Heatmap(state)
        StatsRow(state)
        if (state.totalSeconds > 0 || state.booksFinished > 0 || state.chaptersRead > 0) {
            YearInBooksCard(year = LocalDate.ofEpochDay(state.today).year) {
                context.startActivity(Intent(context, YearInBooksActivity::class.java))
            }
        }
        if (prefs.enabled) {
            BadgesSection(state)
            PerksSection(state)
        }
        SettingsCard(
            state = state,
            onGoal = rewards::setGoalMinutes,
            onReminder = { on ->
                if (on && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                rewards.settings.update { it.copy(reminderEnabled = on) }
            },
            onReminderTime = { minutes -> rewards.settings.update { it.copy(reminderAt = minutes) } },
            onVacation = rewards::setVacation,
            onEnabled = rewards::setEnabled,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(20.dp),
    ) { content() }
}

@Composable
private fun StreakCard(state: ProgressState) {
    val goal = state.prefs.goalSeconds
    val fraction by animateFloatAsState((state.todaySeconds.toFloat() / goal).coerceIn(0f, 1f), spring(dampingRatio = 0.8f), label = "goal")
    val golden = state.unlockedLevel >= Perks.GOLDEN_FLAME_LEVEL
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (state.currentStreak > 0) "🔥" else "🪵", fontSize = 40.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.currentStreak}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = if (state.currentStreak > 0) (if (golden) FlameGold else FlameOrange) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        state.prefs.vacationSince != null -> "Vacation mode: your streak is paused"
                        state.currentStreak == 0 -> "Read ${state.prefs.goalMinutes} min to start a streak"
                        state.todayDone -> "day streak · today's done"
                        else -> "day streak · read today to keep it"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "🧊 ".repeat(state.prefs.freezes).ifEmpty { "No streak freezes yet · " } +
                        if (state.prefs.freezes > 0) "${state.prefs.freezes} freeze${if (state.prefs.freezes == 1) "" else "s"}" else "earn one every 7 days",
                    style = MaterialTheme.typography.labelMedium,
                    color = IceBlue,
                )
            }
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(96.dp),
                    strokeWidth = 10.dp,
                    strokeCap = StrokeCap.Round,
                    color = if (state.todayDone) FlameOrange else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.todayDone) "✓" else "${state.todaySeconds / 60}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.todayDone) "goal met" else "of ${state.prefs.goalMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The last seven days, ending today, so the streak reads as the run of days it is. */
@Composable
private fun WeekStrip(state: ProgressState) {
    val today = LocalDate.ofEpochDay(state.today)
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (6L downTo 0L).forEach { daysAgo ->
            val date = today.minusDays(daysAgo)
            val record = state.days[date.toEpochDay()]
            val isToday = date == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                record?.goalMet == true -> FlameOrange.copy(alpha = 0.18f)
                                record?.frozen == true -> IceBlue.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        )
                        .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            record?.goalMet == true -> "🔥"
                            record?.frozen == true -> "🧊"
                            (record?.activeSeconds ?: 0) > 0 -> "·"
                            else -> ""
                        },
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelCard(state: ProgressState) {
    val next = Perks.all.firstOrNull { it.level > state.level }
    val progress by animateFloatAsState(state.levelProgress, label = "xp")
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⭐", fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level ${state.level}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.xp} XP · ${Levels.xpForLevel(state.level + 1) - state.xp} XP to level ${state.level + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            next?.let {
                Spacer(Modifier.height(10.dp))
                Text("Next reward at level ${it.level}: ${it.title}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The last 18 weeks of reading, one square per day, like a contributions graph. */
@Composable
private fun Heatmap(state: ProgressState) {
    val weeks = 18
    val today = LocalDate.ofEpochDay(state.today)
    val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
    val goal = state.prefs.goalSeconds
    val primary = MaterialTheme.colorScheme.primary
    Card {
        Column {
            Text("Last ${weeks} weeks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(weeks) { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(7) { dayIndex ->
                            val date = firstMonday.plusDays((week * 7 + dayIndex).toLong())
                            val record = state.days[date.toEpochDay()]
                            val seconds = record?.activeSeconds ?: 0
                            val color = when {
                                date.isAfter(today) -> Color.Transparent
                                record?.frozen == true && seconds == 0L -> IceBlue.copy(alpha = 0.5f)
                                seconds == 0L -> MaterialTheme.colorScheme.surfaceVariant
                                seconds < goal -> primary.copy(alpha = 0.3f)
                                seconds < goal * 3 -> primary.copy(alpha = 0.65f)
                                else -> primary
                            }
                            Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(color))
                        }
                    }
                }
            }
        }
    }
}

/** Opens the annual recap. */
@Composable
private fun YearInBooksCard(year: Int, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B1B4D), Color(0xFFB4462A))))
            .clickable(onClick = onOpen)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$year in Books", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Your year of reading, recapped",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun StatsRow(state: ProgressState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("⏱️", formatHours(state.totalSeconds), "read", Modifier.weight(1f))
        Stat("📚", "${state.booksThisYear}", "books this year", Modifier.weight(1f))
        Stat("📑", "${state.chaptersRead}", "chapters", Modifier.weight(1f))
        Stat("🏆", "${state.longestStreak}", "best streak", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(emoji: String, value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 20.sp)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun formatHours(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgesSection(state: ProgressState) {
    val earned = state.earned.associateBy { it.badgeId }
    var selected by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Badges · ${earned.size} of ${BadgeCatalog.all.size}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BadgeCatalog.all.forEach { badge ->
                val got = badge.id in earned
                val secret = badge.hidden && !got
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable { selected = badge.id }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (got) FlameGold.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (secret) "?" else badge.emoji, fontSize = 26.sp, modifier = Modifier.alpha(if (got) 1f else 0.35f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (secret) "Secret" else badge.title,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = if (got) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    selected?.let { id ->
        val badge = BadgeCatalog[id] ?: return@let
        val got = earned[id]
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { Text(if (badge.hidden && got == null) "?" else badge.emoji, fontSize = 40.sp) },
            title = { Text(if (badge.hidden && got == null) "Secret badge" else badge.title) },
            text = {
                Text(
                    when {
                        got != null -> "${badge.description}\n\nEarned ${java.text.DateFormat.getDateInstance().format(java.util.Date(got.earnedAt))}"
                        badge.hidden -> "Keep reading. You'll know it when it happens."
                        else -> badge.description
                    },
                )
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun PerksSection(state: ProgressState) {
    Column {
        Text("Rewards", style = MaterialTheme.typography.titleMedium)
        Perks.all.forEach { perk ->
            val unlocked = state.unlockedLevel >= perk.level
            ListItem(
                leadingContent = { Text(if (unlocked) "✨" else "🔒", fontSize = 20.sp) },
                headlineContent = { Text(perk.title) },
                supportingContent = { Text(if (unlocked) "Unlocked" else "Level ${perk.level}") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.alpha(if (unlocked) 1f else 0.6f),
            )
        }
        if (Perks.all.any { it.theme != null && state.unlockedLevel >= it.level }) {
            Text(
                "Pick unlocked themes from Aa in any reader.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        AppIconPicker(state.unlockedLevel)
    }
}

private class IconLook(val background: Brush, val foreground: Int)

private val iconLooks = mapOf(
    AppIcon.CLASSIC to IconLook(Brush.linearGradient(listOf(Color(0xFFE9692C), Color(0xFFE9692C))), R.drawable.ic_launcher_foreground),
    AppIcon.PAPER to IconLook(Brush.linearGradient(listOf(Color(0xFFF6EEDC), Color(0xFFF6EEDC))), R.drawable.ic_launcher_foreground_paper),
    AppIcon.BLOSSOM to IconLook(Brush.linearGradient(listOf(Color(0xFFEC8FAA), Color(0xFFEC8FAA))), R.drawable.ic_launcher_foreground),
    AppIcon.MIDNIGHT to IconLook(Brush.linearGradient(listOf(Color(0xFF0F1A2E), Color(0xFF0F1A2E))), R.drawable.ic_launcher_foreground_midnight),
    AppIcon.GOLD to IconLook(Brush.linearGradient(listOf(Color(0xFFF8CB5A), Color(0xFFDE8A1C))), R.drawable.ic_launcher_foreground),
)

/** Launcher icon choices; locked ones say which level unlocks them. */
@Composable
private fun AppIconPicker(unlockedLevel: Int) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    var confirming by remember { mutableStateOf<AppIcon?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    Column {
        Text("App icon", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon.entries.forEach { icon ->
                val look = iconLooks.getValue(icon)
                val unlocked = Perks.isUnlocked(icon, unlockedLevel)
                val selected = icon == current
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(look.background)
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
                            .clickable {
                                when {
                                    selected -> Unit
                                    unlocked -> confirming = icon
                                    else -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                        hint = "Reach level ${Perks.unlockLevel(icon)} to unlock the ${icon.title} icon"
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painterResource(look.foreground), contentDescription = null, modifier = Modifier.size(84.dp).alpha(if (unlocked) 1f else 0.35f))
                        if (!unlocked) Icon(Icons.Outlined.Lock, contentDescription = "Locked", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (unlocked) icon.title else "Level ${Perks.unlockLevel(icon)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        hint?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    confirming?.let { icon ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Use the ${icon.title} icon?") },
            text = { Text("Your home screen may take a moment to update. If Books disappears from your home screen, add it again from the app drawer.") },
            confirmButton = {
                TextButton(onClick = {
                    AppIcons.set(context, icon)
                    current = icon
                    confirming = null
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }) { Text("Change icon") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    state: ProgressState,
    onGoal: (Int) -> Unit,
    onReminder: (Boolean) -> Unit,
    onReminderTime: (Int) -> Unit,
    onVacation: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    val prefs = state.prefs
    val haptic = LocalHapticFeedback.current
    val toggled = { on: Boolean -> haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
    var pickingTime by remember { mutableStateOf(false) }
    Card {
        Column {
            Text("Daily goal", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = prefs.goalMinutes == minutes,
                        onClick = {
                            if (prefs.goalMinutes != minutes) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onGoal(minutes)
                        },
                        label = { Text("$minutes min") },
                    )
                }
            }
            val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
            if (prefs.enabled) {
                ListItem(
                    headlineContent = { Text("Daily reminder") },
                    supportingContent = {
                        Text(
                            "%02d:%02d · only if you haven't read yet".format(prefs.reminderAt / 60, prefs.reminderAt % 60),
                            modifier = Modifier.clickable(enabled = prefs.reminderEnabled) { pickingTime = true },
                        )
                    },
                    trailingContent = { Switch(prefs.reminderEnabled, onCheckedChange = { toggled(it); onReminder(it) }) },
                    colors = transparent,
                    modifier = Modifier.clickable(enabled = prefs.reminderEnabled) { pickingTime = true },
                )
                ListItem(
                    headlineContent = { Text("Vacation mode") },
                    supportingContent = { Text("Pause your streak without losing it") },
                    trailingContent = { Switch(prefs.vacationSince != null, onCheckedChange = { toggled(it); onVacation(it) }) },
                    colors = transparent,
                )
            }
            ListItem(
                headlineContent = { Text("Streaks & rewards") },
                supportingContent = {
                    Text(if (prefs.enabled) "Turn off for just stats: no streaks, badges or reminders" else "Off: reading time is still counted for your stats")
                },
                trailingContent = { Switch(prefs.enabled, onCheckedChange = { toggled(it); onEnabled(it) }) },
                colors = transparent,
            )
            Text(
                "Everything here stays on your phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    if (pickingTime) {
        val picker = rememberTimePickerState(initialHour = prefs.reminderAt / 60, initialMinute = prefs.reminderAt % 60)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Remind me at") },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    onReminderTime(picker.hour * 60 + picker.minute)
                    pickingTime = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
        )
    }
}
