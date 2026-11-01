package io.github.dishan18.books.discover

import io.github.dishan18.books.BooksApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Online book discovery, created the first time Discover (or a book download) needs it. */
class DiscoverModule(app: BooksApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Shares the manga module's HTTP stack (one connection pool and cache) and encrypted credential store.
    val sources = BookSources(app.database.bookSources(), app.manga.network, app.manga.credentials)
    val downloads = BookDownloads(app)

    init {
        scope.launch { sources.ensureDefaults() }
    }
}
