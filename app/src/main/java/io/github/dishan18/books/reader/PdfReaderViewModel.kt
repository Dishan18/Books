package io.github.dishan18.books.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val book: Book, val document: PdfDocument, val initialPage: Int) : State
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
                val document = withContext(Dispatchers.IO) { PdfDocument(repository.fileOf(book)) }
                val page = book.location?.toIntOrNull()?.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)) ?: 0
                State.Ready(book, document, page)
            } catch (e: Exception) {
                State.Failed("Couldn't open this PDF. ${e.message.orEmpty()}")
            }
            repository.markOpened(bookId)
        }
    }

    fun onPageSettled(page: Int, pageCount: Int) {
        viewModelScope.launch {
            repository.saveLocation(bookId, page.toString(), progressOf(page, pageCount))
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
                        label = "Page ${page + 1}",
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
