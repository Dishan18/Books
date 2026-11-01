package io.github.dishan18.books.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.ui.BookCover
import io.github.dishan18.books.ui.PressableBox
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BookMimeTypes = arrayOf("application/pdf", "application/epub+zip")

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpen: (Book, Rect) -> Unit,
    onDiscover: () -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    topBarState: TopAppBarState = rememberTopAppBarState(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.import(it)
    }
    val addBooks = { picker.launch(BookMimeTypes) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    var selected by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onDiscover) {
                        Icon(Icons.Outlined.TravelExplore, contentDescription = "Find free books online")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !books.isNullOrEmpty(), enter = fadeIn(), exit = fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = addBooks,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Add books") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            val list = books
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyLibrary(onAdd = addBooks, onDiscover = onDiscover, modifier = Modifier.padding(padding))
                else -> LibraryGrid(
                    books = list,
                    state = gridState,
                    padding = padding,
                    onOpen = onOpen,
                    onLongPress = { selected = it },
                )
            }
            AnimatedVisibility(
                visible = importing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    selected?.let { book ->
        BookActionsSheet(
            book = book,
            onDismiss = { selected = null },
            onDelete = {
                viewModel.delete(book)
                selected = null
            },
        )
    }
}

@Composable
private fun LibraryGrid(
    books: List<Book>,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    padding: PaddingValues,
    onOpen: (Book, Rect) -> Unit,
    onLongPress: (Book) -> Unit,
) {
    val current = books
        .filter { it.lastOpenedAt != null && it.progress < 0.995f }
        .maxByOrNull { it.lastOpenedAt!! }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = state,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 112.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (current != null) {
            item(key = "current", span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(
                    book = current,
                    onOpen = { onOpen(current, it) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.animateItem(), verticalAlignment = Alignment.Bottom) {
                Text("All books", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${books.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(books, key = { it.id }) { book ->
            BookTile(
                book = book,
                onOpen = { onOpen(book, it) },
                onLongPress = { onLongPress(book) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun BookTile(book: Book, onOpen: (Rect) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    PressableBox(onClick = onOpen, onLongClick = onLongPress, modifier = modifier) {
        Column {
            BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(10.dp))
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                statusOf(book),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusOf(book: Book): String {
    val progress = when {
        book.progress >= 0.995f -> "Finished"
        book.lastOpenedAt == null -> "New"
        else -> "${(book.progress * 100).roundToInt()}%"
    }
    return "$progress · ${book.format.name}"
}

@Composable
private fun ContinueReadingCard(book: Book, onOpen: (Rect) -> Unit, modifier: Modifier = Modifier) {
    PressableBox(onClick = onOpen, onLongClick = null, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, Modifier.width(76.dp).aspectRatio(2f / 3f), elevation = 4f)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CONTINUE READING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f).height(5.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(book.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit, onDiscover: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add PDFs and EPUBs from your phone. They're copied into the app, so they stay here even if you delete the originals.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add books")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDiscover) { Text("Find free books online") }
    }
}

@Composable
private fun BookActionsSheet(book: Book, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(56.dp).aspectRatio(2f / 3f), elevation = 3f)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(book.author, statusOf(book)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ListItem(
            headlineContent = { Text("Remove from library") },
            leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { confirming = true }),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove “${book.title}”?") },
            text = { Text("The book, your place in it and its bookmarks will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDelete() }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
