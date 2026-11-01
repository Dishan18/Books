package io.github.dishan18.books.manga.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.SeriesPage
import io.github.dishan18.books.manga.source.api.SourceSeries
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.api.sourceError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class CatalogState(
    val sourceName: String = "",
    val supportsLatest: Boolean = false,
    val listing: Listing = Listing.POPULAR,
    val query: String? = null,
    val items: List<SourceSeries> = emptyList(),
    val nextPage: Int = 1,
    val hasNext: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One source's catalog: popular/latest listings and search, loaded page by page. */
class CatalogViewModel(private val module: MangaModule, val sourceId: String) : ViewModel() {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            runSource { module.sources.require(sourceId) }.onSuccess { source ->
                _state.update { it.copy(sourceName = source.name, supportsLatest = source.supportsLatest) }
                loadMore()
            }.onFailure { e ->
                _state.update { it.copy(error = e.sourceError.message, hasNext = false) }
            }
        }
    }

    fun setListing(listing: Listing) {
        if (_state.value.listing == listing && _state.value.query == null) return
        reset { it.copy(listing = listing, query = null) }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        reset { it.copy(query = trimmed.ifEmpty { null }) }
    }

    fun clearSearch() {
        if (_state.value.query != null) reset { it.copy(query = null) }
    }

    fun retry() {
        _state.update { it.copy(error = null, hasNext = true) }
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || !current.hasNext || job?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val result: Result<SeriesPage> = runSource {
                val source = module.sources.require(sourceId)
                current.query?.let { source.search(it, current.nextPage) } ?: source.browse(current.listing, current.nextPage)
            }
            module.sources.recordResult(sourceId, result.exceptionOrNull()?.sourceError)
            _state.update { state ->
                result.fold(
                    onSuccess = { page ->
                        val known = state.items.mapTo(HashSet()) { it.key }
                        state.copy(
                            items = state.items + page.items.filter { it.key !in known },
                            nextPage = state.nextPage + 1,
                            hasNext = page.hasNextPage && page.items.isNotEmpty(),
                            loading = false,
                        )
                    },
                    onFailure = { state.copy(loading = false, error = it.sourceError.message) },
                )
            }
        }
    }

    /** Saves the series to the database (not the library) and returns its id for navigation. */
    fun open(item: SourceSeries, onOpened: (Long) -> Unit) {
        viewModelScope.launch { onOpened(module.repository.upsertFromCatalog(sourceId, item)) }
    }

    private fun reset(change: (CatalogState) -> CatalogState) {
        job?.cancel()
        _state.update { change(it).copy(items = emptyList(), nextPage = 1, hasNext = true, loading = false, error = null) }
        loadMore()
    }
}

data class SourceResults(
    val sourceId: String,
    val sourceName: String,
    val loading: Boolean = true,
    val items: List<SourceSeries> = emptyList(),
    val error: String? = null,
)

/** Searches every enabled source in parallel; each source succeeds, fails or times out alone. */
class GlobalSearchViewModel(private val module: MangaModule, initialQuery: String) : ViewModel() {
    private val _query = MutableStateFlow(initialQuery)
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<SourceResults>>(emptyList())
    val results: StateFlow<List<SourceResults>> = _results.asStateFlow()
    private var job: Job? = null

    init {
        search(initialQuery)
    }

    fun search(query: String) {
        val trimmed = query.trim()
        _query.value = trimmed
        if (trimmed.isEmpty()) return
        job?.cancel()
        val sources = module.sources.entries.value.filter { it.config.enabled && it.usable }
        _results.value = sources.map { SourceResults(it.id, it.config.name) }
        job = viewModelScope.launch {
            sources.map { entry ->
                async {
                    val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        runSource { entry.source!!.search(trimmed, 1) }
                    } ?: Result.failure(IllegalStateException("Timed out"))
                    module.sources.recordResult(entry.id, result.exceptionOrNull()?.sourceError)
                    _results.update { list ->
                        list.map { r ->
                            if (r.sourceId != entry.id) {
                                r
                            } else {
                                result.fold(
                                    onSuccess = { r.copy(loading = false, items = it.items) },
                                    onFailure = { r.copy(loading = false, error = it.sourceError.message) },
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun open(sourceId: String, item: SourceSeries, onOpened: (Long) -> Unit) {
        viewModelScope.launch { onOpened(module.repository.upsertFromCatalog(sourceId, item)) }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
    }
}
