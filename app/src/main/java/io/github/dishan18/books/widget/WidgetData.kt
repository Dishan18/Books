package io.github.dishan18.books.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.dishan18.books.app
import io.github.dishan18.books.manga.data.MangaRepository
import io.github.dishan18.books.rewards.engine.Streaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface ReadingTarget {
    data class Book(val id: String) : ReadingTarget
    data class Chapter(val id: Long) : ReadingTarget
}

/** The book or series read most recently, ready to resume. */
data class WidgetReading(
    val title: String,
    val subtitle: String?,
    val progress: Float,
    val progressLabel: String,
    val cover: Bitmap?,
    val target: ReadingTarget,
)

data class WidgetSnapshot(
    val reading: WidgetReading?,
    val rewardsOn: Boolean,
    val streak: Int,
    val todayDone: Boolean,
    val todayMinutes: Int,
    val goalMinutes: Int,
    val vacation: Boolean,
)

/** Reads what the widget shows straight from the database, the same rules as the app uses. */
object WidgetData {
    private const val COVER_W = 240
    private const val COVER_H = 360

    suspend fun load(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        val app = context.app
        val db = app.database
        val prefs = app.rewards.settings.value
        val today = LocalDate.now().toEpochDay()
        val days = db.rewards().allDays().associateBy { it.day }
        val streak = Streaks.compute(days, today)
        WidgetSnapshot(
            reading = currentReading(context),
            rewardsOn = prefs.enabled,
            streak = streak.current,
            todayDone = streak.todayDone,
            todayMinutes = ((days[today]?.activeSeconds ?: 0) / 60).toInt(),
            goalMinutes = prefs.goalMinutes,
            vacation = prefs.vacationSince != null,
        )
    }

    private suspend fun currentReading(context: Context): WidgetReading? {
        val app = context.app
        val db = app.database
        // Same "continue reading" rule as the library: opened, and not finished.
        val book = db.books().getAll().filter { it.lastOpenedAt != null && it.progress < 0.995f }.maxByOrNull { it.lastOpenedAt!! }
        val manga = db.manga().observeHistory(1).first().firstOrNull()
        if (book == null && manga == null) return null

        if (manga != null && (book == null || manga.readAt > book.lastOpenedAt!!)) {
            val chapters = db.manga().getChapters(manga.seriesId)
            val resume = MangaRepository.resumeTarget(chapters) ?: chapters.firstOrNull { it.id == manga.chapterId }
            val read = chapters.count { it.read }
            return WidgetReading(
                title = manga.seriesTitle,
                subtitle = resume?.name ?: manga.chapterName,
                progress = if (chapters.isEmpty()) 0f else read.toFloat() / chapters.size,
                progressLabel = "$read/${chapters.size}",
                cover = remoteCover(context, manga.coverUrl) ?: generatedCover(manga.seriesTitle),
                target = ReadingTarget.Chapter(resume?.id ?: manga.chapterId),
            )
        }
        val b = book!!
        val file = app.repository.coverOf(b)
        return WidgetReading(
            title = b.title,
            subtitle = b.author,
            progress = b.progress,
            progressLabel = "${(b.progress * 100).roundToInt()}%",
            cover = (if (b.hasCover) decodeFile(file.path) else null)?.let(::shape) ?: generatedCover(b.title),
            target = ReadingTarget.Book(b.id),
        )
    }

    private fun decodeFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_W) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Manga covers come from Coil's disk cache when the app has shown them, so this rarely touches the network. */
    private suspend fun remoteCover(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        val request = ImageRequest.Builder(context).data(url).size(COVER_W, COVER_H).allowHardware(false).build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull() as? SuccessResult ?: return null
        return shape(result.image.toBitmap())
    }

    /** Crops to a 2:3 cover and rounds the corners (widgets can't clip images themselves). */
    private fun shape(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
        val scale = max(COVER_W.toFloat() / source.width, COVER_H.toFloat() / source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((COVER_W - source.width * scale) / 2f, (COVER_H - source.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat()), RADIUS, RADIUS, paint)
        return out
    }

    private val palettes = listOf(
        0xFF2E4057.toInt() to 0xFF4B6584.toInt(),
        0xFF6D2E46.toInt() to 0xFFA26769.toInt(),
        0xFF1B4332.toInt() to 0xFF40916C.toInt(),
        0xFF3D348B.toInt() to 0xFF7678ED.toInt(),
        0xFF7F4F24.toInt() to 0xFFB08968.toInt(),
        0xFF22223B.toInt() to 0xFF4A4E69.toInt(),
    )

    /** The same generated cover the library draws for books without one. */
    private fun generatedCover(title: String): Bitmap {
        val (top, bottom) = palettes[Math.floorMod(title.hashCode(), palettes.size)]
        val out = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, COVER_H.toFloat(), bottom, top, Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(RectF(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat()), RADIUS, RADIUS, paint)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val width = COVER_W - 40
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(20f, (COVER_H - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return out
    }

    private const val RADIUS = 14f
}
