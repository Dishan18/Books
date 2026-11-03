package io.github.dishan18.books.office

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dishan18.books.app
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.office.sheet.SheetViewerActivity
import io.github.dishan18.books.office.sheet.SheetViewerViewModel
import io.github.dishan18.books.office.slides.SlidesReaderActivity
import io.github.dishan18.books.reader.EXTRA_BOOK_ID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real pinches, drags and flings on both viewers (injected as touch events, two fingers where
 * needed): zooming far past both limits, quickly and repeatedly, while panning, across rotation
 * and sheet or slide changes. Any crash fails the run; screenshots show what was drawn.
 */
@RunWith(AndroidJUnit4::class)
class OfficeZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private val added = mutableListOf<Book>()

    @Before
    fun setUp() {
        context = instrumentation.targetContext
    }

    @After
    fun tearDown() = runBlocking {
        added.forEach { context.app.repository.delete(it) }
    }

    private fun import(name: String): Book = runBlocking {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        context.app.repository.import(Uri.fromFile(file)).getOrThrow().also { added += it }
    }

    private val width get() = context.resources.displayMetrics.widthPixels.toFloat()
    private val height get() = context.resources.displayMetrics.heightPixels.toFloat()

    @Test
    fun spreadsheetZoomsAndScrollsWithoutBreaking() {
        val book = import("sheet.xlsx")
        ActivityScenario.launch<SheetViewerActivity>(Intent(context, SheetViewerActivity::class.java).putExtra(EXTRA_BOOK_ID, book.id)).use { scenario ->
            settle()
            val before = screenshot("sheet-1-opened")
            val cx = width / 2
            val cy = height / 2

            pinch(cx, cy, 150f, 700f) // zoom in
            val zoomedIn = screenshot("sheet-2-zoomed-in")
            assertNotEquals("zooming in changes the view", before.sameAs(zoomedIn), true)
            // A1 is frozen (and yellow): it stays in the corner however far the sheet is zoomed and scrolled.
            assertTrue("frozen A1 still drawn", hasColor(zoomedIn, 0xFFFFFF00.toInt()))
            repeat(4) { pinch(cx, cy, 120f, 900f, steps = 4) } // far past the largest zoom
            drag(cx, cy, cx - 500f, cy - 700f)
            fling(cx, cy + 300f, cx - 300f, cy - 500f)
            assertTrue("frozen A1 still drawn", hasColor(screenshot("sheet-3-max-zoom-panned"), 0xFFFFFF00.toInt()))
            repeat(6) { pinch(cx, cy, 900f, 100f, steps = 4) } // far past the smallest zoom
            screenshot("sheet-4-min-zoom")
            // Quick alternating pinches, as a restless hand would.
            repeat(10) { i -> if (i % 2 == 0) pinch(cx, cy, 200f, 600f, steps = 3) else pinch(cx, cy, 600f, 200f, steps = 3) }
            // A pinch that starts on the headers and moves off the screen's edge.
            pinch(30f, 120f, 50f, width * 2, steps = 6)

            tap(cx, cy)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            settle()
            pinch(height / 2, width / 2, 100f, 500f)
            screenshot("sheet-5-landscape")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            settle()

            scenario.onActivity { ViewModelProvider(it)[SheetViewerViewModel::class.java].select(1) }
            settle()
            pinch(cx, cy, 200f, 800f)
            screenshot("sheet-6-second-sheet")
            scenario.onActivity { ViewModelProvider(it)[SheetViewerViewModel::class.java].select(0) }
            settle()
            pinch(cx, cy, 800f, 200f)
            val after = screenshot("sheet-7-back")
            assertTrue("something is drawn", !isBlank(after))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun slidesZoomAndTurnWithoutBreaking() {
        val book = import("deck.pptx")
        ActivityScenario.launch<SlidesReaderActivity>(Intent(context, SlidesReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, book.id)).use { scenario ->
            settle()
            val cx = width / 2
            val cy = height / 2
            swipe(width * 0.85f, cy, width * 0.15f) // to slide 2
            swipe(width * 0.85f, cy, width * 0.15f) // to slide 3
            val before = screenshot("slides-1-third")

            pinch(cx, cy, 150f, 700f)
            Thread.sleep(800) // the sharp re-render of the zoomed area
            val zoomed = screenshot("slides-2-zoomed")
            assertNotEquals("zooming in changes the view", before.sameAs(zoomed), true)
            repeat(4) { pinch(cx, cy, 120f, 900f, steps = 4) } // past the largest zoom
            drag(cx, cy, cx + 400f, cy + 600f)
            drag(cx, cy, cx - 900f, cy - 900f)
            Thread.sleep(800)
            screenshot("slides-3-max-zoom-panned")
            // A swipe while zoomed pans the slide; it doesn't turn the page.
            swipe(width * 0.85f, cy, width * 0.15f)
            repeat(6) { pinch(cx, cy, 900f, 100f, steps = 4) } // past the smallest zoom: springs back
            settle()
            repeat(10) { i -> if (i % 2 == 0) pinch(cx, cy, 200f, 600f, steps = 3) else pinch(cx, cy, 600f, 200f, steps = 3) }
            pinch(cx, cy, 700f, 150f) // back out to the whole slide
            settle()

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            settle()
            pinch(height / 2, width / 2, 100f, 500f)
            Thread.sleep(800)
            screenshot("slides-4-landscape-zoomed")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            settle()
            pinch(cx, cy, 700f, 120f)
            settle()
            swipe(width * 0.15f, cy, width * 0.85f) // back a slide
            settle()
            val after = screenshot("slides-5-back")
            assertTrue("something is drawn", !isBlank(after))
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    // Touch input

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(700)
        instrumentation.waitForIdleSync()
    }

    private fun send(event: MotionEvent) {
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun event(down: Long, action: Int, points: List<Pair<Float, Float>>): MotionEvent {
        val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /** Two fingers on a horizontal line through ([cx], [cy]), from [from] apart to [to] apart. */
    private fun pinch(cx: Float, cy: Float, from: Float, to: Float, steps: Int = 10) {
        fun at(span: Float) = listOf(cx - span / 2 to cy, cx + span / 2 to cy)
        val down = SystemClock.uptimeMillis()
        send(event(down, MotionEvent.ACTION_DOWN, at(from).take(1)))
        send(event(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(from)))
        for (i in 1..steps) {
            send(event(down, MotionEvent.ACTION_MOVE, at(from + (to - from) * i / steps)))
            Thread.sleep(8)
        }
        send(event(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(to)))
        send(event(down, MotionEvent.ACTION_UP, at(to).take(1)))
        instrumentation.waitForIdleSync()
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 20, pause: Long = 10) {
        val down = SystemClock.uptimeMillis()
        send(event(down, MotionEvent.ACTION_DOWN, listOf(x0 to y0)))
        for (i in 1..steps) {
            send(event(down, MotionEvent.ACTION_MOVE, listOf(x0 + (x1 - x0) * i / steps to y0 + (y1 - y0) * i / steps)))
            Thread.sleep(pause)
        }
        send(event(down, MotionEvent.ACTION_UP, listOf(x1 to y1)))
        instrumentation.waitForIdleSync()
    }

    private fun fling(x0: Float, y0: Float, x1: Float, y1: Float) = drag(x0, y0, x1, y1, steps = 6, pause = 4)

    private fun swipe(x0: Float, y: Float, x1: Float) {
        drag(x0, y, x1, y, steps = 12, pause = 6)
        settle()
    }

    private fun tap(x: Float, y: Float) = drag(x, y, x, y, steps = 1, pause = 0)

    private fun screenshot(name: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        context.getExternalFilesDir("zoom-test")?.let { dir -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bitmap
    }

    private fun hasColor(bitmap: Bitmap, color: Int): Boolean {
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getPixel(x, y) == color) return true
        return false
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val step = 37
        for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step step) for (x in 0 until bitmap.width step step) if (bitmap.getPixel(x, y) != first) return false
        return true
    }
}
