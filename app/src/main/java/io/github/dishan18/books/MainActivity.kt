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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.library.LibraryScreen
import io.github.dishan18.books.library.LibraryViewModel
import io.github.dishan18.books.reader.readerIntent
import io.github.dishan18.books.ui.theme.BooksTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels {
        viewModelFactory { initializer { LibraryViewModel(app.repository) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BooksTheme {
                LibraryScreen(viewModel, onOpen = ::openBook)
            }
        }
        if (savedInstanceState == null) importFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
    }

    /** The book grows out of its cover, so opening it feels physical. */
    private fun openBook(book: Book, coverBounds: Rect) {
        val options = ActivityOptionsCompat.makeScaleUpAnimation(
            window.decorView,
            coverBounds.left.toInt(),
            coverBounds.top.toInt(),
            coverBounds.width.toInt().coerceAtLeast(1),
            coverBounds.height.toInt().coerceAtLeast(1),
        )
        startActivity(readerIntent(this, book), options.toBundle())
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
