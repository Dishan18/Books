package io.github.dishan18.books.manga.reader

import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.rewards.RewardsRepository
import io.github.dishan18.books.manga.source.api.runSource
import io.github.dishan18.books.manga.source.api.sourceError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PageStatus {
    data object Waiting : PageStatus
    data class Loading(val progress: Float) : PageStatus
    data class Ready(val file: File, val width: Int, val height: Int) : PageStatus
    data class Failed(val message: String) : PageStatus
}

/** One page of a loaded chapter. [status] is Compose state so progress updates redraw only this page. */
class ReaderPage(val chapterId: Long, val index: Int) {
    var status by mutableStateOf<PageStatus>(PageStatus.Waiting)
}

class ReaderChapter(val loaded: LoadedChapter, val pages: List<ReaderPage>) {
    val chapter get() = loaded.chapter
}

/** Rows of the vertical list. Keys stay stable when a placeholder turns into strips. */
sealed interface ReaderItem {
    val key: String

    data class Header(val chapter: MangaChapter) : ReaderItem {
        override val key = "h:${chapter.id}"
    }

    data class Placeholder(val page: ReaderPage, val height: Int) : ReaderItem {
        override val key = "p:${page.chapterId}:${page.index}:0"
    }

    data class PageStrip(
        val page: ReaderPage,
        val file: File,
        val imageWidth: Int,
        val strip: Strip,
        val stripIndex: Int,
        val stripCount: Int,
        val sampleSize: Int,
        /** Width the strip is shown at (the viewport width). */
        val displayWidth: Int,
        /** Display pixels of this page above this strip. */
        val offsetInPage: Int,
        val pageHeight: Int,
    ) : ReaderItem {
        override val key = "p:${page.chapterId}:${page.index}:$stripIndex"
    }

    data class End(val state: EndState) : ReaderItem {
        override val key = "end"
    }
}

sealed interface EndState {
    data object Loading : EndState
    data class NextReady(val next: MangaChapter) : EndState
    data object NoMore : EndState
    data class Failed(val message: String) : EndState
}

/** Where to scroll: a page and how far through it (0..1). */
data class ScrollTarget(val chapterId: Long, val page: Int, val fraction: Float)

data class ReaderPosition(val chapter: MangaChapter, val page: Int, val pageCount: Int, val fraction: Float)

@OptIn(FlowPreview::class)
class WebtoonViewModel(
    private val module: MangaModule,
    private val initialChapterId: Long,
    private val rewards: RewardsRepository,
) : ViewModel() {
    private val repository = module.repository
    private val loader = PageLoader(module.sources, module.downloads.storage, File(module.app.cacheDir, "manga-pages"))
    val decoder = StripDecoder()

    private val _series = MutableStateFlow<MangaSeries?>(null)
    val series: StateFlow<MangaSeries?> = _series.asStateFlow()

    private val _fatalError = MutableStateFlow<String?>(null)
    val fatalError: StateFlow<String?> = _fatalError.asStateFlow()

    private val chapters = mutableListOf<ReaderChapter>()
    private var endState: EndState = EndState.Loading

    private val _items = MutableStateFlow<List<ReaderItem>>(emptyList())
    val items: StateFlow<List<ReaderItem>> = _items.asStateFlow()

    private val _position = MutableStateFlow<ReaderPosition?>(null)
    val position: StateFlow<ReaderPosition?> = _position.asStateFlow()

    private val _pendingScroll = MutableStateFlow<ScrollTarget?>(null)
    val pendingScroll: StateFlow<ScrollTarget?> = _pendingScroll.asStateFlow()

    /** Pixel distances to scroll by (volume keys, edge taps). */
    private val scrollRequests = Channel<Float>(Channel.CONFLATED)
    val scrollEvents = scrollRequests.receiveAsFlow()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private val fetchPermits = Semaphore(3)
    private val fetchJobs = mutableMapOf<String, Job>()
    private var nextChapterJob: Job? = null
    private var openedChapterId: Long? = null
    private var latestPosition: ReaderPosition? = null
    private val powerManager = module.app.getSystemService(PowerManager::class.java)
    private val markedRead = mutableSetOf<Long>()
    private val progressUpdates = MutableStateFlow<ReaderPosition?>(null)

    init {
        viewModelScope.launch { openInitial() }
        viewModelScope.launch {
            progressUpdates.filterNotNull().debounce(400).distinctUntilChanged().collect { save(it) }
        }
    }

    private suspend fun openInitial() {
        val chapter = repository.getChapter(initialChapterId)
        val series = chapter?.let { repository.getSeries(it.seriesId) }
        if (chapter == null || series == null) {
            _fatalError.value = "This chapter is no longer in your library."
            return
        }
        _series.value = series
        val result = runSource { loader.open(series, chapter) }
        result.onFailure {
            _fatalError.value = it.sourceError.message
            return
        }
        val loaded = result.getOrThrow()
        chapters += ReaderChapter(loaded, List(loaded.pageCount) { ReaderPage(chapter.id, it) })
        val startAtBeginning = chapter.read && chapter.lastPageRead >= loaded.pageCount - 1
        _pendingScroll.value = if (startAtBeginning) {
            ScrollTarget(chapter.id, 0, 0f)
        } else {
            ScrollTarget(chapter.id, chapter.lastPageRead.coerceIn(0, loaded.pageCount - 1), chapter.pageOffset)
        }
        updateEndState()
        rebuild()
        val target = _pendingScroll.value!!
        prefetchAround(chapters.first(), target.page)
        markOpened(chapter)
    }

    fun onViewport(width: Int, height: Int) {
        if (width == viewportWidth && height == viewportHeight || width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        rebuild()
    }

    /** Called as the list scrolls with the first visible item (and its offset) and the last visible item. */
    fun onScroll(firstIndex: Int, firstOffset: Int, lastIndex: Int) {
        val list = _items.value
        val first = list.getOrNull(firstIndex) ?: return
        val position = positionOf(first, firstOffset) ?: return
        // Until the saved position has been restored, don't let the list's starting point overwrite it.
        val pending = _pendingScroll.value
        if (pending != null && (position.chapter.id != pending.chapterId || position.page != pending.page)) return
        // The controls only show the page, so only publish page changes; this runs every frame while scrolling.
        val shown = _position.value
        if (shown == null || shown.chapter.id != position.chapter.id || shown.page != position.page) _position.value = position
        latestPosition = position
        progressUpdates.value = position
        countPageTurn(position)

        val readerChapter = chapters.firstOrNull { it.chapter.id == position.chapter.id } ?: return
        prefetchAround(readerChapter, position.page)
        if (readerChapter.chapter.id != openedChapterId) markOpened(readerChapter.chapter)

        // Chapters the reader has scrolled past count as read. A chapter is also finished once what
        // follows it (the next chapter's heading, or the end marker) is on screen: with short last
        // pages the list hits bottom before the last page reaches the top.
        val finishedId = finishedChapterId(list.getOrNull(lastIndex))
        val readToEnd = position.page == position.pageCount - 1 && position.fraction > 0.6f
        chapters.filter {
            it.chapter.sortIndex < position.chapter.sortIndex || it.chapter.id == finishedId ||
                (readToEnd && it.chapter.id == position.chapter.id)
        }
            .filter { !it.chapter.read && it.chapter.id !in markedRead }
            .forEach { markRead(it) }

        // Load the next chapter while the reader is still a few pages away from the end.
        if (readerChapter === chapters.last() && position.page >= position.pageCount - 3) loadNextChapter()
    }

    private var lastCounted: ReaderPosition? = null

    /** Scrolling forward onto another page counts as a page turned, as in the book readers. */
    private fun countPageTurn(position: ReaderPosition) {
        val last = lastCounted
        val forward = last != null && (
            if (position.chapter.id == last.chapter.id) position.page > last.page else position.chapter.sortIndex > last.chapter.sortIndex
            )
        if (forward) rewards.pageTurned()
        lastCounted = position
    }

    /** The chapter that [lastVisible] directly follows, if it marks the end of one. */
    private fun finishedChapterId(lastVisible: ReaderItem?): Long? = when (lastVisible) {
        is ReaderItem.End -> chapters.lastOrNull()?.chapter?.id
        is ReaderItem.Header -> chapters.indexOfFirst { it.chapter.id == lastVisible.chapter.id }
            .takeIf { it > 0 }?.let { chapters[it - 1].chapter.id }
        else -> null
    }

    private fun positionOf(item: ReaderItem, offsetPx: Int): ReaderPosition? {
        val (page, fraction) = when (item) {
            is ReaderItem.PageStrip -> item.page to ((item.offsetInPage + offsetPx).toFloat() / item.pageHeight).coerceIn(0f, 1f)
            is ReaderItem.Placeholder -> item.page to 0f
            is ReaderItem.Header -> {
                val chapter = chapters.firstOrNull { it.chapter.id == item.chapter.id } ?: return null
                chapter.pages.firstOrNull()?.let { it to 0f } ?: return null
            }
            is ReaderItem.End -> {
                val last = chapters.lastOrNull() ?: return null
                last.pages.lastOrNull()?.let { it to 1f } ?: return null
            }
        }
        val chapter = chapters.firstOrNull { it.chapter.id == page.chapterId } ?: return null
        return ReaderPosition(chapter.chapter, page.index, chapter.pages.size, fraction)
    }

    /** Item index and pixel offset for [target], and whether the page is laid out exactly yet. */
    fun resolve(target: ScrollTarget, list: List<ReaderItem>): Triple<Int, Int, Boolean>? {
        val strips = list.withIndex().filter { (_, item) ->
            item is ReaderItem.PageStrip && item.page.chapterId == target.chapterId && item.page.index == target.page
        }
        if (strips.isNotEmpty()) {
            val pageHeight = (strips.first().value as ReaderItem.PageStrip).pageHeight
            val wanted = (target.fraction * pageHeight).toInt()
            val hit = strips.lastOrNull { (it.value as ReaderItem.PageStrip).offsetInPage <= wanted } ?: strips.first()
            return Triple(hit.index, wanted - (hit.value as ReaderItem.PageStrip).offsetInPage, true)
        }
        val placeholder = list.indexOfFirst {
            it is ReaderItem.Placeholder && it.page.chapterId == target.chapterId && it.page.index == target.page
        }
        return if (placeholder >= 0) Triple(placeholder, 0, target.fraction == 0f) else null
    }

    fun consumePendingScroll() {
        _pendingScroll.value = null
    }

    fun cancelPendingScroll() = consumePendingScroll()

    fun jumpToPage(page: Int) {
        val position = _position.value ?: return
        val chapter = chapters.firstOrNull { it.chapter.id == position.chapter.id } ?: return
        val index = page.coerceIn(0, chapter.pages.size - 1)
        prefetchAround(chapter, index)
        _pendingScroll.value = ScrollTarget(chapter.chapter.id, index, 0f)
    }

    fun scrollBy(fractionOfScreen: Float) {
        scrollRequests.trySend(viewportHeight * fractionOfScreen)
    }

    /** Replaces the list with another chapter (previous/next buttons). */
    fun openChapter(chapter: MangaChapter) {
        viewModelScope.launch {
            val series = _series.value ?: return@launch
            fetchJobs.values.forEach { it.cancel() }
            fetchJobs.clear()
            nextChapterJob?.cancel()
            val result = runSource { loader.open(series, chapter) }
            result.onFailure {
                _fatalError.value = null
                endState = EndState.Failed(it.sourceError.message)
                rebuild()
                return@launch
            }
            val loaded = result.getOrThrow()
            chapters.clear()
            chapters += ReaderChapter(loaded, List(loaded.pageCount) { ReaderPage(chapter.id, it) })
            updateEndState()
            _pendingScroll.value = ScrollTarget(chapter.id, 0, 0f)
            rebuild()
            prefetchAround(chapters.first(), 0)
            markOpened(chapter)
        }
    }

    suspend fun previousChapter(): MangaChapter? = _position.value?.chapter?.let { repository.previousChapter(it) }

    suspend fun nextChapter(): MangaChapter? = _position.value?.chapter?.let { repository.nextChapter(it) }

    fun retry(page: ReaderPage) {
        page.status = PageStatus.Waiting
        val chapter = chapters.firstOrNull { it.chapter.id == page.chapterId } ?: return
        ensureLoaded(chapter, page.index, force = true)
        rebuild()
    }

    fun retryNextChapter() {
        endState = EndState.Loading
        rebuild()
        loadNextChapter(force = true)
    }

    private fun prefetchAround(chapter: ReaderChapter, page: Int) {
        // Current page first, then the next few, then the one before. Battery Saver fetches less ahead.
        val ahead = if (powerManager?.isPowerSaveMode == true) PREFETCH_AHEAD_POWER_SAVE else PREFETCH_AHEAD
        (listOf(page) + (page + 1..page + ahead) + (page - 1)).forEach { ensureLoaded(chapter, it) }
        val index = chapters.indexOf(chapter)
        if (page >= chapter.pages.size - 2) chapters.getOrNull(index + 1)?.let { next -> (0..2).forEach { ensureLoaded(next, it) } }
    }

    private fun ensureLoaded(chapter: ReaderChapter, index: Int, force: Boolean = false) {
        val page = chapter.pages.getOrNull(index) ?: return
        if (!force && page.status !is PageStatus.Waiting) return
        val key = "${page.chapterId}:$index"
        if (fetchJobs[key]?.isActive == true) return
        page.status = PageStatus.Loading(-1f)
        fetchJobs[key] = viewModelScope.launch {
            fetchPermits.withPermit {
                val result = runSource {
                    val file = loader.file(chapter.loaded, index) { progress -> page.status = PageStatus.Loading(progress) }
                    val size = withContext(Dispatchers.IO) { imageSize(file) }
                        ?: throw IllegalStateException("This page isn't a readable image")
                    PageStatus.Ready(file, size.first, size.second)
                }
                page.status = result.getOrElse { PageStatus.Failed(it.sourceError.message) }
            }
            rebuild()
        }
    }

    private fun loadNextChapter(force: Boolean = false) {
        if (nextChapterJob?.isActive == true) return
        val last = chapters.lastOrNull() ?: return
        if (!force && (endState is EndState.NoMore || endState is EndState.Failed)) return
        nextChapterJob = viewModelScope.launch {
            val series = _series.value ?: return@launch
            val next = repository.nextChapter(last.chapter)
            if (next == null) {
                endState = EndState.NoMore
                rebuild()
                return@launch
            }
            endState = EndState.Loading
            rebuild()
            val result = runSource { loader.open(series, next) }
            result.onSuccess { loaded ->
                chapters += ReaderChapter(loaded, List(loaded.pageCount) { ReaderPage(next.id, it) })
                updateEndState()
            }.onFailure {
                endState = EndState.Failed(it.sourceError.message)
            }
            rebuild()
        }
    }

    private suspend fun updateEndState() {
        val last = chapters.lastOrNull() ?: return
        endState = repository.nextChapter(last.chapter)?.let { EndState.NextReady(it) } ?: EndState.NoMore
    }

    private fun markOpened(chapter: MangaChapter) {
        openedChapterId = chapter.id
        viewModelScope.launch { repository.recordOpened(chapter) }
    }

    private fun markRead(chapter: ReaderChapter) {
        if (!markedRead.add(chapter.chapter.id)) return
        viewModelScope.launch {
            repository.setRead(listOf(chapter.chapter.id), true)
            // Chapters read here (not ones ticked off in a list) count towards badges.
            val series = _series.value ?: return@launch
            val allRead = repository.getChapters(series.id).all { it.read }
            rewards.onChaptersRead(series.id, series.title, listOf(chapter.chapter.id), seriesCompleted = allRead && series.status == SeriesStatus.COMPLETED)
        }
    }

    private suspend fun save(position: ReaderPosition) {
        withContext(NonCancellable) {
            repository.saveProgress(position.chapter.id, position.page, position.fraction, position.pageCount)
        }
    }

    /** Rebuilds the list rows from chapter and page state. */
    private fun rebuild() {
        if (viewportWidth <= 0) return
        val maxStrip = viewportHeight.coerceAtLeast(1)
        val placeholderHeight = (viewportHeight * 0.8f).toInt().coerceAtLeast(1)
        val rows = mutableListOf<ReaderItem>()
        chapters.forEach { chapter ->
            rows += ReaderItem.Header(chapter.chapter)
            chapter.pages.forEach { page ->
                when (val status = page.status) {
                    is PageStatus.Ready -> {
                        val strips = StripPlanner.plan(status.width, status.height, viewportWidth, maxStrip)
                        val sample = StripPlanner.sampleSize(status.width, viewportWidth)
                        val pageHeight = strips.sumOf { it.displayHeight }
                        var offset = 0
                        strips.forEachIndexed { i, strip ->
                            rows += ReaderItem.PageStrip(page, status.file, status.width, strip, i, strips.size, sample, viewportWidth, offset, pageHeight)
                            offset += strip.displayHeight
                        }
                    }
                    else -> rows += ReaderItem.Placeholder(page, placeholderHeight)
                }
            }
        }
        if (chapters.isNotEmpty()) rows += ReaderItem.End(endState)
        _items.value = rows
    }

    override fun onCleared() {
        latestPosition?.let { position ->
            module.scope.launch { repository.saveProgress(position.chapter.id, position.page, position.fraction, position.pageCount) }
        }
        module.scope.launch { loader.trimCache() }
        decoder.clear()
    }

    private companion object {
        const val PREFETCH_AHEAD = 4
        const val PREFETCH_AHEAD_POWER_SAVE = 2
    }
}
