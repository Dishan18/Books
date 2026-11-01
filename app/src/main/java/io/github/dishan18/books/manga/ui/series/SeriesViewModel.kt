package io.github.dishan18.books.manga.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaDownload
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.source.api.sourceError
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesViewModel(private val module: MangaModule, private val seriesId: Long) : ViewModel() {
    private val repository = module.repository
    private val downloadManager = module.downloads

    val series: StateFlow<MangaSeries?> = repository.observeSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<MangaChapter>?> = repository.observeChapters(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloads: StateFlow<Map<Long, MangaDownload>> = downloadManager.observeForSeries(seriesId)
        .map { list -> list.associateBy { it.chapterId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val isOnlineSource: Boolean get() = series.value?.let { module.sources.peek(it.sourceId)?.isOnline } ?: true

    init {
        viewModelScope.launch {
            val current = repository.getSeries(seriesId) ?: return@launch
            val stale = current.chaptersUpdatedAt?.let { System.currentTimeMillis() - it > STALE_MS } ?: true
            if (current.detailsUpdatedAt == null || stale) refresh()
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            val details = async { repository.refreshDetails(seriesId) }
            val chapters = async { repository.refreshChapters(seriesId) }
            val failure = details.await().exceptionOrNull() ?: chapters.await().exceptionOrNull()
            // Offline with chapters already stored is fine: say so quietly instead of blocking.
            if (failure != null) {
                val message = failure.sourceError.message
                if (this@SeriesViewModel.chapters.value.isNullOrEmpty()) _error.value = message else _messages.send(message)
            }
            _refreshing.value = false
        }
    }

    fun toggleFavorite() {
        val current = series.value ?: return
        viewModelScope.launch {
            repository.setFavorite(seriesId, !current.favorite)
            _messages.send(if (current.favorite) "Removed from library" else "Added to library")
        }
    }

    fun continueReading(open: (Long) -> Unit) {
        viewModelScope.launch {
            val chapter = repository.continueChapter(seriesId)
            if (chapter != null) open(chapter.id) else _messages.send("No chapters available yet")
        }
    }

    fun setRead(chapters: List<MangaChapter>, read: Boolean) {
        viewModelScope.launch { repository.setRead(chapters.map { it.id }, read) }
    }

    fun markPreviousRead(chapter: MangaChapter) {
        viewModelScope.launch { repository.markPreviousRead(chapter) }
    }

    fun download(chapters: List<MangaChapter>) {
        if (!isOnlineSource) return
        viewModelScope.launch {
            val existing = downloads.value
            val wanted = chapters.filter { existing[it.id]?.state != DownloadState.COMPLETED }
            downloadManager.enqueue(wanted)
            if (wanted.size > 1) _messages.send("Downloading ${wanted.size} chapters")
        }
    }

    fun downloadUnread() {
        val list = chapters.value ?: return
        download(list.filter { !it.read })
    }

    fun retryOrResume(chapter: MangaChapter) {
        viewModelScope.launch { downloadManager.resume(chapter.id) }
    }

    fun pause(chapter: MangaChapter) {
        viewModelScope.launch { downloadManager.pause(chapter.id) }
    }

    fun removeDownload(chapters: List<MangaChapter>) {
        viewModelScope.launch { downloadManager.remove(chapters.map { it.seriesId to it.id }) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            val ids = downloads.first().keys
            val list = chapters.value.orEmpty().filter { it.id in ids }
            downloadManager.remove(list.map { it.seriesId to it.id })
            _messages.send("Deleted ${list.size} downloaded chapter${if (list.size == 1) "" else "s"}")
        }
    }

    private companion object {
        const val STALE_MS = 60 * 60 * 1000L
    }
}
