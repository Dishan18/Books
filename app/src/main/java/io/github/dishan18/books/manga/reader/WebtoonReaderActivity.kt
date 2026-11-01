package io.github.dishan18.books.manga.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.reader.applyReaderWindow
import io.github.dishan18.books.ui.theme.BooksTheme

/** Vertical webtoon/manga reader for one chapter, continuing into the following chapters. */
class WebtoonReaderActivity : ComponentActivity() {

    private val viewModel: WebtoonViewModel by viewModels {
        viewModelFactory { initializer { WebtoonViewModel(app.manga, intent.getLongExtra(EXTRA_CHAPTER_ID, -1), app.rewards) } }
    }

    private val screenAwake = ScreenAwake(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyReaderWindow(ReaderTheme.DARK, chromeVisible = false)
        setContent {
            BooksTheme(darkTheme = true) {
                WebtoonReaderScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onChromeChange = { visible -> applyReaderWindow(ReaderTheme.DARK, visible) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenAwake.poke()
        app.rewards.resume(ReadingKind.MANGA) { viewModel.series.value?.let { it.id.toString() to it.title } }
    }

    override fun onPause() {
        super.onPause()
        screenAwake.stop()
        app.rewards.pause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        screenAwake.poke()
        app.rewards.ping()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        screenAwake.poke()
        app.rewards.ping()
        return super.dispatchKeyEvent(event)
    }

    /** Volume keys scroll by most of a screen. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.scrollBy(0.8f); true }
        KeyEvent.KEYCODE_VOLUME_UP -> { viewModel.scrollBy(-0.8f); true }
        else -> super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_CHAPTER_ID = "chapterId"

        fun intent(context: Context, chapterId: Long): Intent =
            Intent(context, WebtoonReaderActivity::class.java).putExtra(EXTRA_CHAPTER_ID, chapterId)
    }
}
