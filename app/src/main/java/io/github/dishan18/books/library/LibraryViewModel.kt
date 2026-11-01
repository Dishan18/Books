package io.github.dishan18.books.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.BookRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {

    /** Null until the database has answered, so the UI doesn't flash the empty state. */
    val books: StateFlow<List<Book>?> = repository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            val results = uris.map { repository.import(it) }
            _importing.value = false

            val added = results.count { it.isSuccess }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            val message = when {
                failures.isEmpty() && added == 1 -> "Added “${results.first().getOrThrow().title}”"
                failures.isEmpty() -> "Added $added books"
                added == 0 && failures.size == 1 -> "Couldn't add this file: ${failures.first().message}"
                else -> "Added $added, ${failures.size} couldn't be opened"
            }
            _messages.send(message)
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch { repository.delete(book) }
    }
}
