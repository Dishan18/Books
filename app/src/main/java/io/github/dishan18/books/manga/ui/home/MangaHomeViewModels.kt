package io.github.dishan18.books.manga.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.manga.data.DownloadItem
import io.github.dishan18.books.manga.data.HistoryEntry
import io.github.dishan18.books.manga.data.LibrarySeries
import io.github.dishan18.books.manga.download.DownloadSettings
import io.github.dishan18.books.manga.download.LibraryUpdateWorker
import io.github.dishan18.books.manga.source.SourceRegistry
import io.github.dishan18.books.manga.source.SourceType
import io.github.dishan18.books.manga.source.local.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaLibraryViewModel(private val module: MangaModule) : ViewModel() {
    private val repository = module.repository

    val library: StateFlow<List<LibrarySeries>?> = repository.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val history: StateFlow<List<HistoryEntry>?> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val updating: StateFlow<Boolean> = WorkManager.getInstance(module.app)
        .getWorkInfosForUniqueWorkFlow(LibraryUpdateWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    fun refresh() = LibraryUpdateWorker.start(module.app)

    fun removeFromLibrary(seriesId: Long) {
        viewModelScope.launch { repository.setFavorite(seriesId, false) }
    }

    fun clearHistory(seriesId: Long) {
        viewModelScope.launch { repository.clearHistory(seriesId) }
    }

    /** Finds where to resume a series and hands back the chapter to open. */
    fun resume(seriesId: Long, open: (Long) -> Unit) {
        viewModelScope.launch {
            var chapter = repository.continueChapter(seriesId)
            if (chapter == null) {
                repository.refreshChapters(seriesId)
                chapter = repository.continueChapter(seriesId)
            }
            if (chapter != null) open(chapter.id) else _messages.send("No chapters to read yet")
        }
    }

    fun suggestTitle(uris: List<Uri>): String =
        module.importer.suggestTitle(uris.map { module.importer.displayName(it) })

    fun importArchives(uris: List<Uri>, title: String) = runImport { module.importer.importArchives(uris, title) }

    fun importFolder(uri: Uri) = runImport { module.importer.importFolder(uri) }

    private fun runImport(block: suspend () -> ImportResult) {
        viewModelScope.launch {
            _importing.value = true
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            _importing.value = false
            _messages.send(
                result.fold(
                    onSuccess = { r ->
                        val chapters = "${r.chapters} chapter${if (r.chapters == 1) "" else "s"}"
                        if (r.skipped.isEmpty()) "Added “${r.title}” ($chapters)" else "Added “${r.title}” ($chapters), skipped ${r.skipped.size}"
                    },
                    onFailure = { "Couldn't import: ${it.message ?: "unknown error"}" },
                ),
            )
        }
    }
}

class BrowseViewModel(private val module: MangaModule) : ViewModel() {
    val sources: StateFlow<List<SourceRegistry.Entry>> = module.sources.entries

    /** A one-line description that says something the source's name doesn't. */
    fun description(entry: SourceRegistry.Entry): String = when (entry.type) {
        SourceType.MANGADEX -> "Official public API"
        SourceType.KOMGA -> module.sources.komgaSettings(entry.config)?.baseUrl
            ?.removePrefix("https://")?.removePrefix("http://") ?: "Your server"
        SourceType.LOCAL, null -> "Imported CBZ, ZIP and image folders"
    }
}

class DownloadsViewModel(private val module: MangaModule) : ViewModel() {
    private val manager = module.downloads

    val queue: StateFlow<List<DownloadItem>?> = manager.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<DownloadSettings> = manager.settings

    fun pause(item: DownloadItem) = launch { manager.pause(item.download.chapterId) }
    fun resume(item: DownloadItem) = launch { manager.resume(item.download.chapterId) }
    fun cancel(item: DownloadItem) = launch { manager.remove(listOf(item.download.seriesId to item.download.chapterId)) }
    fun pauseAll() = launch { manager.pauseAll() }
    fun resumeAll() = launch { manager.resumeAll() }
    fun updateSettings(settings: DownloadSettings) = manager.updateSettings(settings)

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
