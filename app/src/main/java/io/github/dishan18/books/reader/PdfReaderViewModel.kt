package io.github.dishan18.books.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.Bookmark
import io.github.dishan18.books.office.slides.SlideDocument
import io.github.dishan18.books.rewards.RewardsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages of a PDF, or (with [slides]) of a PowerPoint presentation, which reads the same way except
 * that reaching the last slide doesn't count as finishing a book.
 */
class PdfReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val rewards: RewardsRepository,
    private val slides: Boolean = false,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val book: Book, val document: PageDocument, val initialPage: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    val bookmarks = repository.bookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val book = repository.get(bookId)
            if (book == null) {
                _state.value = State.Failed("This book is no longer in your library.")
                return@launch
            }
            _state.value = try {
                val document = withContext(Dispatchers.IO) {
                    val file = repository.fileOf(book)
                    if (slides) SlideDocument.open(file) else PdfDocument(file)
                }
                val page = book.location?.toIntOrNull()?.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)) ?: 0
                State.Ready(book, document, page)
            } catch (e: Exception) {
                State.Failed((if (slides) "Couldn't open these slides. " else "Couldn't open this PDF. ") + e.message.orEmpty())
            }
            repository.markOpened(bookId)
        }
    }

    private var firstSettle = true
    private var lastSettledPage = -1

    fun onPageSettled(page: Int, pageCount: Int) {
        viewModelScope.launch {
            repository.saveLocation(bookId, page.toString(), progressOf(page, pageCount))
        }
        // Moving forward counts as a page read; jumps count once, going back doesn't count.
        if (lastSettledPage in 0 until page) rewards.pageTurned()
        lastSettledPage = page
        // Turning onto the last page finishes the book (reopening one already there doesn't).
        val opened = firstSettle
        firstSettle = false
        if (!slides && !opened && pageCount > 0 && page == pageCount - 1) {
            (state.value as? State.Ready)?.let { rewards.onBookFinished(bookId, it.book.title, pageCount) }
        }
    }

    fun toggleBookmark(page: Int, pageCount: Int) {
        viewModelScope.launch {
            val existing = bookmarks.value.firstOrNull { it.location == page.toString() }
            if (existing != null) {
                repository.deleteBookmark(existing)
            } else {
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId,
                        location = page.toString(),
                        label = "${if (slides) "Slide" else "Page"} ${page + 1}",
                        progress = progressOf(page, pageCount),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.deleteBookmark(bookmark) }
    }

    private fun progressOf(page: Int, pageCount: Int) =
        if (pageCount <= 1) 1f else (page + 1).toFloat() / pageCount

    override fun onCleared() {
        (_state.value as? State.Ready)?.document?.close()
    }
}
