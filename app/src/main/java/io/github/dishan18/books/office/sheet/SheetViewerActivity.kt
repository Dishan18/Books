package io.github.dishan18.books.office.sheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dishan18.books.app
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.reader.EXTRA_BOOK_ID
import io.github.dishan18.books.ui.theme.BooksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope

/**
 * Shows an Excel workbook. Only for looking: it's never counted as reading, so it doesn't touch
 * streaks, reading time, Year in Books or "Continue reading".
 */
class SheetViewerActivity : ComponentActivity() {

    private val viewModel: SheetViewerViewModel by viewModels {
        viewModelFactory { initializer { SheetViewerViewModel(intent.getStringExtra(EXTRA_BOOK_ID).orEmpty(), app.repository) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BooksTheme {
                SheetViewerScreen(viewModel, onClose = ::finish)
            }
        }
    }
}

class SheetViewerViewModel(private val bookId: String, private val repository: BookRepository) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val book: Book, val sheets: List<SheetInfo>) : State
        data class Failed(val message: String) : State
    }

    sealed interface SheetState {
        data object Loading : SheetState
        data class Shown(val sheet: Sheet) : SheetState
        data class Failed(val message: String) : SheetState
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    private val _selected = MutableStateFlow(0)
    val selected = _selected.asStateFlow()

    private val _sheet = MutableStateFlow<SheetState>(SheetState.Loading)
    val sheet = _sheet.asStateFlow()

    /** Reads happen one at a time, off the main thread. */
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var workbook: Workbook? = null
    private val loaded = LinkedHashMap<Int, Sheet>()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val book = repository.get(bookId)
            if (book == null) {
                _state.value = State.Failed("This file is no longer in your library.")
                return@launch
            }
            try {
                val opened = withContext(io) { Workbook.open(repository.fileOf(book)) }
                workbook = opened
                _state.value = State.Ready(book, opened.sheets)
                // Back to the sheet that was open last time.
                select(book.location?.toIntOrNull()?.coerceIn(0, opened.sheets.lastIndex) ?: 0)
            } catch (e: Exception) {
                _state.value = State.Failed("Couldn't open this spreadsheet. ${e.message.orEmpty()}")
            }
            repository.markOpened(bookId)
        }
    }

    fun select(index: Int) {
        val book = (_state.value as? State.Ready) ?: return
        if (index !in book.sheets.indices) return
        _selected.value = index
        loaded[index]?.let {
            _sheet.value = SheetState.Shown(it)
        } ?: run {
            _sheet.value = SheetState.Loading
        }
        // Remembered so the workbook opens on this sheet next time; never counted as progress.
        viewModelScope.launch { repository.saveLocation(bookId, index.toString(), 0f) }
        if (loaded.containsKey(index)) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = runCatching { withContext(io) { workbook!!.readSheet(index) } }
            if (_selected.value != index) return@launch
            result.onSuccess { sheet ->
                loaded[index] = sheet
                // Keep the last few sheets read, so switching back is instant.
                while (loaded.size > KEEP_SHEETS) loaded.remove(loaded.keys.first())
                _sheet.value = SheetState.Shown(sheet)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _sheet.value = SheetState.Failed(e.message ?: "This sheet couldn't be shown.")
            }
        }
    }

    override fun onCleared() {
        val book = workbook ?: return
        // Close after any read in flight.
        CoroutineScope(io).launch { book.close() }
    }

    private companion object {
        const val KEEP_SHEETS = 3
    }
}

@Composable
private fun SheetViewerScreen(viewModel: SheetViewerViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selected.collectAsStateWithLifecycle()
    val sheetState by viewModel.sheet.collectAsStateWithLifecycle()
    var selectedCell by remember { mutableStateOf<CellRange?>(null) }
    LaunchedEffect(selectedIndex) { selectedCell = null }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? SheetViewerViewModel.State.Ready)?.book?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            val ready = state as? SheetViewerViewModel.State.Ready ?: return@Scaffold
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                val shown = (sheetState as? SheetViewerViewModel.SheetState.Shown)?.sheet
                val cell = selectedCell
                if (shown != null && cell != null) {
                    CellDetails(shown, cell)
                    HorizontalDivider()
                }
                if (ready.sheets.size > 1) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(ready.sheets) { index, info ->
                            FilterChip(
                                selected = index == selectedIndex,
                                onClick = {
                                    if (index != selectedIndex) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    viewModel.select(index)
                                },
                                label = { Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SheetViewerViewModel.State.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SheetViewerViewModel.State.Failed -> Message(s.message, onClose, Modifier.align(Alignment.Center))
                is SheetViewerViewModel.State.Ready -> when (val sheet = sheetState) {
                    SheetViewerViewModel.SheetState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is SheetViewerViewModel.SheetState.Failed -> Message(sheet.message, onClose, Modifier.align(Alignment.Center))
                    is SheetViewerViewModel.SheetState.Shown -> Column(Modifier.fillMaxSize()) {
                        if (sheet.sheet.truncated) {
                            Text(
                                "This sheet is very large. Showing the first ${"%,d".format(Workbook.MAX_CELLS)} cells.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (sheet.sheet.isEmpty && sheet.sheet.merges.isEmpty()) {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    "This sheet is empty.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        } else {
                            SheetGrid(
                                sheet = sheet.sheet,
                                selected = selectedCell,
                                onSelect = { range ->
                                    if (range != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    selectedCell = range
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The selected cell's address and its full value, which can be selected and copied. */
@Composable
private fun CellDetails(sheet: Sheet, range: CellRange) {
    val name = Workbook.columnName(range.firstCol) + (range.firstRow + 1) +
        if (range.lastRow != range.firstRow || range.lastCol != range.firstCol) ":" + Workbook.columnName(range.lastCol) + (range.lastRow + 1) else ""
    val value = sheet.text(range.firstRow, range.firstCol)
    Row(
        Modifier.fillMaxWidth().heightIn(max = 140.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                value ?: "Empty",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Message(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center)
        TextButton(onClick = onClose) { Text("Back to library") }
    }
}
