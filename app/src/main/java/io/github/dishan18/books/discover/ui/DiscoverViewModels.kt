package io.github.dishan18.books.discover.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.BooksApp
import io.github.dishan18.books.app
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.discover.BookDownloadStatus
import io.github.dishan18.books.discover.catalog.BookFile
import io.github.dishan18.books.discover.catalog.CatalogItem
import io.github.dishan18.books.discover.catalog.CatalogListing
import io.github.dishan18.books.discover.catalog.CatalogPage
import io.github.dishan18.books.discover.catalog.GutenbergCatalog
import io.github.dishan18.books.discover.catalog.OnlineBook
import io.github.dishan18.books.discover.catalog.OpdsCatalog
import io.github.dishan18.books.discover.data.BookSourceEntity
import io.github.dishan18.books.discover.data.BookSourceType
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.api.sourceError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A ViewModel built from the app. */
@Composable
inline fun <reified VM : ViewModel> discoverViewModel(key: String? = null, crossinline create: (BooksApp) -> VM): VM {
    val app = LocalContext.current.app
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app) } })
}

/** A row of books loading from one catalog. */
data class Shelf(val items: List<OnlineBook> = emptyList(), val loading: Boolean = true, val error: String? = null)

class DiscoverHomeViewModel(private val app: BooksApp) : ViewModel() {
    private val sources = app.discover.sources
    val catalogs: StateFlow<List<BookSourceEntity>> = sources.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _popular = MutableStateFlow(Shelf())
    val popular: StateFlow<Shelf> = _popular.asStateFlow()

    init {
        loadPopular()
    }

    fun loadPopular() {
        _popular.value = Shelf(_popular.value.items, loading = true)
        viewModelScope.launch {
            val result = runSource {
                val catalog = sources.catalog(GutenbergCatalog.DEFAULT_ID) ?: error("Project Gutenberg isn't available")
                catalog.browse(CatalogListing.POPULAR)
            }
            _popular.value = result.fold(
                onSuccess = { page -> Shelf(page.books().take(20).onEach(sources::remember), loading = false) },
                onFailure = { Shelf(_popular.value.items, loading = false, error = it.sourceError.message) },
            )
        }
    }

    fun headers(sourceId: String, url: String?) = sources.imageHeaders(sourceId, url)
}

data class CatalogState(
    val name: String = "",
    val title: String? = null,
    val listings: List<CatalogListing> = emptyList(),
    val listing: CatalogListing? = null,
    val query: String? = null,
    val canSearch: Boolean = false,
    val folders: List<CatalogItem.Folder> = emptyList(),
    val books: List<OnlineBook> = emptyList(),
    val nextUrl: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** One catalog, or one folder inside an OPDS catalog when [folderUrl] is set. */
class CatalogViewModel(app: BooksApp, private val sourceId: String, private val folderUrl: String?, folderTitle: String?) : ViewModel() {
    private val sources = app.discover.sources
    private val _state = MutableStateFlow(CatalogState(title = folderTitle))
    val state: StateFlow<CatalogState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val catalog = sources.catalog(sourceId)
            if (catalog == null) {
                _state.value = _state.value.copy(loading = false, error = "This catalog was removed")
                return@launch
            }
            val listing = catalog.listings.first().takeIf { folderUrl == null }
            _state.value = _state.value.copy(
                name = catalog.name,
                listings = if (folderUrl == null) catalog.listings.filter { it != CatalogListing.START } else emptyList(),
                listing = listing,
                canSearch = catalog !is OpdsCatalog || catalog.supportsSearch(),
            )
            load(reset = true)
        }
    }

    fun setListing(listing: CatalogListing) {
        if (listing == _state.value.listing && _state.value.query == null) return
        _state.value = _state.value.copy(listing = listing, query = null)
        load(reset = true)
    }

    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = _state.value.copy(query = query.trim())
        load(reset = true)
    }

    fun clearSearch() {
        if (_state.value.query == null) return
        _state.value = _state.value.copy(query = null)
        load(reset = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.nextUrl == null || current.error != null) return
        load(reset = false)
    }

    fun retry() = load(reset = _state.value.books.isEmpty() && _state.value.folders.isEmpty())

    private fun load(reset: Boolean) {
        job?.cancel()
        val before = _state.value
        _state.value = if (reset) before.copy(folders = emptyList(), books = emptyList(), nextUrl = null, loading = true, error = null) else before.copy(loading = true, error = null)
        job = viewModelScope.launch {
            val result = runSource {
                val catalog = sources.catalog(sourceId) ?: error("This catalog was removed")
                val next = _state.value.nextUrl
                when {
                    !reset && next != null -> catalog.open(next)
                    _state.value.query != null -> catalog.search(_state.value.query!!)
                    folderUrl != null -> catalog.open(folderUrl)
                    else -> catalog.browse(_state.value.listing ?: CatalogListing.START)
                }
            }
            result.onSuccess { page ->
                val books = page.books().onEach(sources::remember)
                val now = _state.value
                _state.value = now.copy(
                    title = now.title ?: page.title,
                    folders = (now.folders + page.items.filterIsInstance<CatalogItem.Folder>()).distinctBy { it.url },
                    books = (now.books + books).distinctBy { it.key },
                    nextUrl = page.nextUrl?.takeIf { it != now.nextUrl },
                    loading = false,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.sourceError.message)
            }
        }
    }

    fun headers(url: String?) = sources.imageHeaders(sourceId, url)
}

data class SearchResult(val sourceId: String, val name: String, val shelf: Shelf)

/** Searches every catalog at once; each one shows up as soon as it answers. */
class BookSearchViewModel(app: BooksApp, initialQuery: String) : ViewModel() {
    private val sources = app.discover.sources
    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()
    private var job: Job? = null

    init {
        search(initialQuery)
    }

    fun search(query: String) {
        if (query.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            val catalogs = sources.all.first()
            _results.value = catalogs.map { SearchResult(it.id, it.name, Shelf()) }
            catalogs.map { config ->
                async {
                    val result = runSource {
                        val catalog = sources.catalog(config.id) ?: error("This catalog was removed")
                        catalog.search(query)
                    }
                    val shelf = result.fold(
                        onSuccess = { page -> Shelf(page.books().onEach(sources::remember), loading = false) },
                        onFailure = { Shelf(loading = false, error = it.sourceError.message) },
                    )
                    _results.value = _results.value.map { if (it.sourceId == config.id) it.copy(shelf = shelf) else it }
                }
            }.awaitAll()
        }
    }

    fun headers(sourceId: String, url: String?) = sources.imageHeaders(sourceId, url)
}

class OnlineBookViewModel(private val app: BooksApp, private val sourceId: String, private val key: String) : ViewModel() {
    private val sources = app.discover.sources
    private val downloads = app.discover.downloads

    private val _book = MutableStateFlow(sources.remembered(sourceId, key))
    val book: StateFlow<OnlineBook?> = _book.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val inLibrary: StateFlow<Book?> = app.repository.observeDownloaded(sourceId, key)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val download: StateFlow<BookDownloadStatus> = downloads.status(sourceId, key)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDownloadStatus.Idle)

    init {
        loadDetails()
    }

    fun loadDetails() {
        _error.value = null
        _loading.value = true
        viewModelScope.launch {
            val result = runSource {
                val catalog = sources.catalog(sourceId) ?: error("This catalog was removed")
                val known = _book.value ?: if (catalog is GutenbergCatalog) {
                    // Gutenberg books can be rebuilt from their number alone (after the app was closed).
                    OnlineBook(sourceId, key, title = "", author = null, coverUrl = null)
                } else {
                    error("Open this book from its catalog again")
                }
                catalog.details(known)
            }
            result.onSuccess {
                sources.remember(it)
                _book.value = it
            }.onFailure { _error.value = it.sourceError.message }
            _loading.value = false
        }
    }

    fun download(file: BookFile) {
        val book = _book.value ?: return
        downloads.enqueue(book, file)
    }

    fun cancel() = downloads.cancel(sourceId, key)

    fun headers(url: String?) = sources.imageHeaders(sourceId, url)

    /** The newly downloaded book, for opening it. */
    suspend fun libraryBook(id: String): Book? = app.repository.get(id)
}

data class CatalogForm(
    val name: String = "",
    val url: String = "https://",
    val username: String = "",
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class CatalogEditViewModel(app: BooksApp, private val sourceId: String?) : ViewModel() {
    private val sources = app.discover.sources
    private val _form = MutableStateFlow(CatalogForm())
    val form: StateFlow<CatalogForm> = _form.asStateFlow()
    val isEditing = sourceId != null

    init {
        if (sourceId != null) {
            viewModelScope.launch {
                val config = sources.get(sourceId)?.takeIf { it.type == BookSourceType.OPDS.name } ?: return@launch
                _form.value = CatalogForm(
                    name = config.name,
                    url = config.url,
                    username = config.username.orEmpty(),
                    hasSavedPassword = sources.hasPassword(sourceId),
                )
            }
        }
    }

    fun update(change: (CatalogForm) -> CatalogForm) {
        _form.value = change(_form.value).copy(error = null)
    }

    /** True when the address isn't HTTPS and isn't on a private network, so traffic could be read. */
    fun isInsecure(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (parsed.isHttps) return false
        val host = parsed.host
        return !(host == "localhost" || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host))
    }

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        val url = form.url.trim()
        if (url.toHttpUrlOrNull() == null) {
            _form.value = form.copy(error = "Enter the catalog's address, for example http://192.168.1.20:8083/opds")
            return
        }
        val username = form.username.trim().ifBlank { null }
        // Editing with an empty password field keeps the saved one.
        val password = form.password.takeIf { it.isNotEmpty() }
        val passwordForTest = password ?: if (form.hasSavedPassword && sourceId != null) sources.savedPassword(sourceId) else null
        viewModelScope.launch {
            _form.value = form.copy(busy = true, error = null)
            val test = sources.test(url, username, passwordForTest)
            if (test.isFailure) {
                _form.value = form.copy(busy = false, error = "Couldn't open the catalog: ${test.exceptionOrNull()!!.sourceError.message}")
                return@launch
            }
            val name = form.name.ifBlank { test.getOrNull().orEmpty() }
            sources.saveOpds(sourceId, name, url, username, password)
            onSaved()
        }
    }

    fun remove(onRemoved: () -> Unit) {
        val id = sourceId ?: return
        viewModelScope.launch {
            sources.remove(id)
            onRemoved()
        }
    }
}

private fun CatalogPage.books(): List<OnlineBook> = items.filterIsInstance<CatalogItem.BookItem>().map { it.book }
