package io.github.dishan18.books.rewards

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.dishan18.books.HomeActivity
import io.github.dishan18.books.R
import io.github.dishan18.books.app
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * One gentle nudge a day at the chosen time, only if today's goal isn't met yet. Scheduled as
 * periodic work (no exact alarms, no wake-ups beyond the one check).
 */
class ReadingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val rewards = applicationContext.app.rewards
        val state = rewards.progress.value
        val prefs = rewards.settings.value
        if (!prefs.enabled || !prefs.reminderEnabled || prefs.vacationSince != null || state.todayDone) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        val minutesLeft = ((prefs.goalSeconds - state.todaySeconds).coerceAtLeast(60) / 60).toInt()
        val (title, text) = if (state.currentStreak > 0) {
            "Keep your ${state.currentStreak}-day streak 🔥" to "$minutesLeft minute${if (minutesLeft == 1) "" else "s"} of reading keeps it going."
        } else {
            "A few pages today? 📖" to "Your goal is ${prefs.goalMinutes} minutes. Start a new streak tonight."
        }
        ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext,
            1,
            Intent(applicationContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationManagerCompat.from(applicationContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "reading-reminder"
        private const val CHANNEL = "reading_reminders"
        private const val NOTIFICATION_ID = 2001

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A daily nudge if you haven't read yet"
                },
            )
        }

        /** Schedules (or cancels) the daily check to match the current settings. */
        fun sync(context: Context, prefs: RewardsPrefs) {
            val work = WorkManager.getInstance(context)
            if (!prefs.enabled || !prefs.reminderEnabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atStartOfDay().plusMinutes(prefs.reminderAt.toLong())
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<ReadingReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
