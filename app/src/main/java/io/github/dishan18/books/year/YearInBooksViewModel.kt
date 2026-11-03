package io.github.dishan18.books.year

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.BooksApp
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.rewards.data.ReadingKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class YearInBooksViewModel(private val app: BooksApp) : ViewModel() {

    data class State(
        val years: List<Int> = emptyList(),
        val year: Int = LocalDate.now().year,
        val recap: YearRecap? = null,
        /** Books still in the library, by id, for covers. */
        val books: Map<String, Book> = emptyMap(),
        val topSeries: MangaSeries? = null,
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Older EPUBs were imported before subjects were kept; read them once so genres are real.
            app.repository.backfillSubjects()
            val years = withContext(Dispatchers.IO) {
                (app.database.rewards().activeDays().map { LocalDate.ofEpochDay(it).year } + LocalDate.now().year)
                    .distinct().sortedDescending()
            }
            _state.value = _state.value.copy(years = years)
            select(LocalDate.now().year)
        }
    }

    fun select(year: Int) {
        loadJob?.cancel()
        _state.value = _state.value.copy(year = year, loading = true)
        loadJob = viewModelScope.launch {
            val rewards = app.database.rewards()
            val range = YearRecaps.range(year)
            val books = app.database.books().getAll().associateBy { it.id }
            val seriesCache = mutableMapOf<Long, MangaSeries?>()
            suspend fun series(id: String) = id.toLongOrNull()?.let { key -> seriesCache.getOrPut(key) { app.database.manga().getSeries(key) } }

            val sessions = rewards.sessionsBetween(range.first, range.last)
            // Look the genres up before building, since build() isn't suspending.
            val genres = sessions.map { it.kind to it.itemId }.distinct().associateWith { (kind, id) ->
                when (kind) {
                    ReadingKind.BOOK -> books[id]?.subjectList.orEmpty()
                    ReadingKind.MANGA -> series(id)?.genreList.orEmpty()
                    ReadingKind.SLIDES -> emptyList()
                }
            }
            val recap = withContext(Dispatchers.Default) {
                YearRecaps.build(
                    year = year,
                    days = rewards.daysBetween(range.first, range.last),
                    sessions = sessions,
                    finished = rewards.finishedBetween(range.first, range.last),
                    firstRecordedDay = rewards.activeDays().minOrNull(),
                    firstPagesDay = rewards.firstPagesDay(),
                    genresOf = { kind, id -> genres[kind to id].orEmpty() },
                )
            }
            _state.value = _state.value.copy(
                recap = recap,
                books = books,
                topSeries = recap.topSeries?.let { series(it.itemId) },
                loading = false,
            )
        }
    }
}
