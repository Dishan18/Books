package io.github.dishan18.books.year

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.ui.theme.BooksTheme
import java.io.File

/** The annual recap, full screen, one highlight per page. */
class YearInBooksActivity : ComponentActivity() {

    private val viewModel: YearInBooksViewModel by viewModels {
        viewModelFactory { initializer { YearInBooksViewModel(app) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BooksTheme(darkTheme = true) {
                YearInBooksScreen(viewModel, onClose = ::finish, onShare = { bitmap, year -> share(this, bitmap, year) })
            }
        }
    }

    companion object {
        /** Saves the summary card to the app's cache and opens the share sheet. Nothing is uploaded by the app. */
        fun share(context: Context, bitmap: Bitmap, year: Int) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "books-$year.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share your $year in Books"))
        }
    }
}
