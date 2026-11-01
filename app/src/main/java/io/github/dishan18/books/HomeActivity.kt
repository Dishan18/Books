package io.github.dishan18.books

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.geometry.Rect
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.library.LibraryViewModel
import io.github.dishan18.books.manga.reader.WebtoonReaderActivity
import io.github.dishan18.books.reader.readerIntent
import io.github.dishan18.books.ui.theme.BooksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {

    /** A tab another screen (the widget) asked for; AppRoot switches to it and clears it. */
    private val requestedTab = MutableStateFlow<AppTab?>(null)

    private val viewModel: LibraryViewModel by viewModels {
        viewModelFactory { initializer { LibraryViewModel(app.repository) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BooksTheme {
                AppRoot(viewModel, onOpenBook = ::openBook, requestedTab = requestedTab)
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_RESUME -> resume(intent)
            ACTION_PROGRESS -> requestedTab.value = AppTab.PROGRESS
            else -> importFrom(intent)
        }
    }

    /** From the widget: straight back into the book or chapter, with the library underneath. */
    private fun resume(intent: Intent) {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1)
        when {
            bookId != null -> lifecycleScope.launch { app.repository.get(bookId)?.let { startActivity(readerIntent(this@HomeActivity, it)) } }
            chapterId >= 0 -> startActivity(WebtoonReaderActivity.intent(this, chapterId))
        }
    }

    /** The book grows out of its cover, so opening it feels physical. */
    private fun openBook(book: Book, coverBounds: Rect) {
        if (coverBounds.isEmpty) {
            startActivity(readerIntent(this, book))
            return
        }
        val options = ActivityOptionsCompat.makeScaleUpAnimation(
            window.decorView,
            coverBounds.left.toInt(),
            coverBounds.top.toInt(),
            coverBounds.width.toInt().coerceAtLeast(1),
            coverBounds.height.toInt().coerceAtLeast(1),
        )
        startActivity(readerIntent(this, book), options.toBundle())
    }

    companion object {
        const val ACTION_RESUME = "io.github.dishan18.books.RESUME"
        const val ACTION_PROGRESS = "io.github.dishan18.books.PROGRESS"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER_ID = "chapterId"
    }

    /** Handles "Open with" and "Share" from other apps. */
    private fun importFrom(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }
        if (uri != null) viewModel.import(listOf(uri))
    }
}
