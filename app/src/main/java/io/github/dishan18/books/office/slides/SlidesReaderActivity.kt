package io.github.dishan18.books.office.slides

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.reader.EXTRA_BOOK_ID
import io.github.dishan18.books.reader.PdfReaderScreen
import io.github.dishan18.books.reader.PdfReaderViewModel
import io.github.dishan18.books.reader.applyReaderWindow
import io.github.dishan18.books.rewards.data.ReadingKind
import io.github.dishan18.books.ui.theme.BooksTheme
import io.github.dishan18.books.widget.ReadingWidget

/**
 * PowerPoint slides, read like PDF pages. Time spent and slides turned count toward the streak
 * (as [ReadingKind.SLIDES], which Year in Books leaves out); reaching the end doesn't finish a book.
 */
class SlidesReaderActivity : ComponentActivity() {

    private val viewModel: PdfReaderViewModel by viewModels {
        viewModelFactory {
            initializer { PdfReaderViewModel(intent.getStringExtra(EXTRA_BOOK_ID).orEmpty(), app.repository, app.rewards, slides = true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = app.readerSettings
        applyReaderWindow(settings.settings.value.theme, chromeVisible = false)
        setContent {
            BooksTheme {
                PdfReaderScreen(
                    viewModel = viewModel,
                    settingsStore = settings,
                    onClose = ::finish,
                    onWindowChange = { theme, chromeVisible -> applyReaderWindow(theme, chromeVisible) },
                    slides = true,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.rewards.resume(ReadingKind.SLIDES) {
            (viewModel.state.value as? PdfReaderViewModel.State.Ready)?.book?.let { it.id to it.title }
        }
    }

    override fun onPause() {
        super.onPause()
        app.rewards.pause()
        ReadingWidget.refresh(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            (viewModel.state.value as? PdfReaderViewModel.State.Ready)?.document?.trim(background = level >= TRIM_MEMORY_BACKGROUND)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.rewards.ping()
        return super.dispatchTouchEvent(event)
    }
}
