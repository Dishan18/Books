package io.github.dishan18.books.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.dishan18.books.HomeActivity
import io.github.dishan18.books.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The "Reading" home-screen widget. Drawn on demand: no periodic updates, no wake-ups. */
class ReadingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ReadingWidget.render(context, ids, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Only matters before Android 12, where the layout is picked from the size here.
        ReadingWidget.render(context, intArrayOf(id), goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReadingWidget.ACTION_NEW_DAY, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                ReadingWidget.render(context, ReadingWidget.ids(context), goAsync())
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) {
        ReadingWidget.cancelNewDay(context)
    }
}

object ReadingWidget {
    internal const val ACTION_NEW_DAY = "io.github.dishan18.books.widget.NEW_DAY"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ReadingWidgetProvider::class.java))

    /**
     * Something the widget shows may have changed (a reader closed, a book was added or removed).
     * Free when no widget is placed; bursts of calls collapse into one redraw.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (ids(app).isEmpty()) return
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                // Readers save their last stretch of reading time as they close; draw after that.
                delay(1_000)
                update(app, ids(app))
            }
        }
    }

    internal fun render(context: Context, ids: IntArray, result: android.content.BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        scope.launch {
            try {
                update(app, ids)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun update(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val snapshot = WidgetData.load(context)
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot, manager.getAppWidgetOptions(id))) }
        scheduleNewDay(context)
    }

    private fun views(context: Context, snapshot: WidgetSnapshot, options: Bundle): RemoteViews {
        val small = { small(context, snapshot) }
        val medium = { detailed(context, snapshot, R.layout.widget_medium) }
        val large = { detailed(context, snapshot, R.layout.widget_large) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher shows the largest of these that fits the widget, and switches as it's resized.
            return RemoteViews(mapOf(SizeF(110f, 110f) to small(), SizeF(220f, 110f) to medium(), SizeF(220f, 210f) to large()))
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return when {
            width in 1 until 220 -> small()
            height >= 210 -> large()
            else -> medium()
        }
    }

    private fun small(context: Context, s: WidgetSnapshot) = RemoteViews(context.packageName, R.layout.widget_small).apply {
        setTextViewText(R.id.flame, if (!s.rewardsOn || s.streak > 0) "🔥" else "🪵")
        if (s.rewardsOn) {
            setTextViewText(R.id.streak_count, "${s.streak}")
            setTextViewText(R.id.streak_label, "day streak")
        } else {
            setTextViewText(R.id.streak_count, "${s.todayMinutes}")
            setTextViewText(R.id.streak_label, "min today")
        }
        setTextViewText(R.id.goal, goalLine(s, short = true))
        setOnClickPendingIntent(android.R.id.background, openReading(context, s.reading))
    }

    private fun detailed(context: Context, s: WidgetSnapshot, layout: Int) = RemoteViews(context.packageName, layout).apply {
        val reading = s.reading
        if (s.rewardsOn) {
            setViewVisibility(R.id.streak, View.VISIBLE)
            setTextViewText(R.id.streak, "${if (s.streak > 0) "🔥" else "🪵"} ${s.streak}")
            setOnClickPendingIntent(R.id.streak, openProgress(context))
        } else {
            setViewVisibility(R.id.streak, View.GONE)
        }
        if (reading == null) {
            setTextViewText(R.id.label, "BOOKS")
            setTextViewText(R.id.title, "Nothing open yet")
            setTextViewText(R.id.subtitle, "Tap to choose something to read")
            setViewVisibility(R.id.progress_row, View.GONE)
            setImageViewResource(R.id.cover, R.mipmap.ic_launcher)
        } else {
            setTextViewText(R.id.label, context.getString(R.string.widget_label))
            setTextViewText(R.id.title, reading.title)
            setTextViewText(R.id.subtitle, reading.subtitle.orEmpty())
            setViewVisibility(R.id.subtitle, if (reading.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.progress_row, View.VISIBLE)
            setProgressBar(R.id.progress, 1000, (reading.progress * 1000).roundToInt(), false)
            setTextViewText(R.id.percent, reading.progressLabel)
            if (reading.cover != null) setImageViewBitmap(R.id.cover, reading.cover) else setImageViewResource(R.id.cover, R.mipmap.ic_launcher)
        }
        if (layout == R.layout.widget_large) setTextViewText(R.id.goal, goalLine(s, short = false))
        setOnClickPendingIntent(android.R.id.background, openReading(context, reading))
    }

    private fun goalLine(s: WidgetSnapshot, short: Boolean): String = when {
        !s.rewardsOn -> if (short) "Tap to read" else "${s.todayMinutes} min read today"
        s.vacation -> "Vacation mode"
        s.todayDone -> if (short) "Done today" else "Today's goal is done. ${s.streak}-day streak"
        else -> {
            val left = (s.goalMinutes - s.todayMinutes).coerceAtLeast(1)
            if (short) "$left min to go" else "Today: ${s.todayMinutes} of ${s.goalMinutes} min. $left more to keep your streak"
        }
    }

    private fun openReading(context: Context, reading: WidgetReading?): PendingIntent {
        val intent = Intent(context, HomeActivity::class.java).setAction(HomeActivity.ACTION_RESUME)
        when (val target = reading?.target) {
            is ReadingTarget.Book -> intent.putExtra(HomeActivity.EXTRA_BOOK_ID, target.id)
            is ReadingTarget.Chapter -> intent.putExtra(HomeActivity.EXTRA_CHAPTER_ID, target.id)
            null -> Unit
        }
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openProgress(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        2,
        Intent(context, HomeActivity::class.java).setAction(HomeActivity.ACTION_PROGRESS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun newDayIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        3,
        Intent(context, ReadingWidgetProvider::class.java).setAction(ACTION_NEW_DAY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Redraws just after midnight so "today" and the streak roll over. An inexact, non-waking alarm:
     * if the phone is asleep it fires the next time the screen comes on, which is when it's seen.
     */
    private fun scheduleNewDay(context: Context) {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, at, newDayIntent(context))
    }

    internal fun cancelNewDay(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(newDayIntent(context))
    }
}
