package io.github.dishan18.books.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.data.ReaderTheme
import io.github.dishan18.books.office.sheet.SheetViewerActivity
import io.github.dishan18.books.office.slides.SlidesReaderActivity

const val EXTRA_BOOK_ID = "bookId"

fun readerIntent(context: Context, book: Book): Intent {
    val activity = when (book.format) {
        BookFormat.PDF -> PdfReaderActivity::class.java
        BookFormat.EPUB -> EpubReaderActivity::class.java
        BookFormat.XLSX -> SheetViewerActivity::class.java
        BookFormat.PPTX -> SlidesReaderActivity::class.java
    }
    return Intent(context, activity).putExtra(EXTRA_BOOK_ID, book.id)
}

/** Paints the window in the reading theme and hides the system bars while reading. */
fun Activity.applyReaderWindow(theme: ReaderTheme, chromeVisible: Boolean) {
    window.setBackgroundDrawable(ColorDrawable(theme.background.toArgb()))
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !theme.isDark
        isAppearanceLightNavigationBars = !theme.isDark
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
    }
}
