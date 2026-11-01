package io.github.dishan18.books.discover.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.discover.BookDownloadStatus
import io.github.dishan18.books.discover.catalog.BookFile
import io.github.dishan18.books.discover.catalog.CatalogItem
import io.github.dishan18.books.discover.catalog.GutenbergCatalog
import io.github.dishan18.books.discover.catalog.OnlineBook
import io.github.dishan18.books.discover.data.BookSourceEntity
import io.github.dishan18.books.discover.data.BookSourceType
import io.github.dishan18.books.manga.ui.common.LoadingState
import io.github.dishan18.books.manga.ui.common.MessageState
import io.github.dishan18.books.manga.ui.common.RemoteCover
import io.github.dishan18.books.ui.PressableBox
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenCatalog: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenBook: (OnlineBook) -> Unit,
    onAddCatalog: () -> Unit,
    onEditCatalog: (String) -> Unit,
) {
    val viewModel = discoverViewModel { DiscoverHomeViewModel(it) }
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val popular by viewModel.popular.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search free books") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            item {
                SectionHeader("Popular on Project Gutenberg", action = "See all") { onOpenCatalog(GutenbergCatalog.DEFAULT_ID) }
                when {
                    popular.items.isNotEmpty() -> BookRow(popular.items, viewModel::headers, onOpenBook)
                    popular.loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> InlineError(popular.error ?: "Couldn't load", onRetry = viewModel::loadPopular)
                }
            }
            item { SectionHeader("Catalogs") }
            items(catalogs, key = { it.id }) { config ->
                CatalogCard(
                    config = config,
                    onClick = { onOpenCatalog(config.id) },
                    onEdit = if (config.type == BookSourceType.OPDS.name) ({ onEditCatalog(config.id) }) else null,
                )
            }
            item {
                TextButton(onClick = onAddCatalog, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add an OPDS catalog")
                }
            }
            item {
                Text(
                    "Project Gutenberg's books are free and in the public domain in the USA. If you're elsewhere, check your country's copyright rules before downloading. Books you download are added to your library and read offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun BookRow(books: List<OnlineBook>, headers: (String, String?) -> Map<String, String>, onOpen: (OnlineBook) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(books, key = { it.sourceId + it.key }) { book ->
            OnlineBookTile(book, headers(book.sourceId, book.coverUrl), onClick = { onOpen(book) }, modifier = Modifier.width(110.dp))
        }
    }
}

@Composable
private fun OnlineBookTile(book: OnlineBook, headers: Map<String, String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressableBox(onClick = { onClick() }, onLongClick = null, modifier = modifier) {
        Column {
            RemoteCover(book.coverUrl, headers, book.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(8.dp))
            Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CatalogCard(config: BookSourceEntity, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    val gutenberg = config.type == BookSourceType.GUTENBERG.name
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(if (gutenberg) Icons.Outlined.Public else Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(config.name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (gutenberg) "75,000+ free public-domain books" else config.url.substringAfter("://").substringBefore('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${config.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Retry") }
    }
}

@Composable
fun BookCatalogScreen(
    sourceId: String,
    folderUrl: String?,
    folderTitle: String?,
    onBack: () -> Unit,
    onOpenFolder: (String, String) -> Unit,
    onOpenBook: (OnlineBook) -> Unit,
) {
    val viewModel = discoverViewModel(key = "book-catalog-$sourceId-$folderUrl") { CatalogViewModel(it, sourceId, folderUrl, folderTitle) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current

    val nearEnd by remember {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 8 } ?: false }
    }
    LaunchedEffect(nearEnd, state.books.size) { if (nearEnd) viewModel.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                title = {
                    if (searching) {
                        SearchField(query, onChange = { query = it }, onSearch = { viewModel.search(query) }, placeholder = "Search ${state.name}")
                    } else {
                        Text(state.title?.takeIf { folderUrl != null } ?: state.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (state.canSearch && folderUrl == null) {
                        IconButton(onClick = {
                            if (searching) {
                                searching = false
                                query = ""
                                viewModel.clearSearch()
                            } else {
                                searching = true
                            }
                        }) {
                            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = if (searching) "Close search" else "Search")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.listings.size > 1 && state.query == null) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.listings.forEach { listing ->
                        FilterChip(
                            selected = state.listing == listing,
                            onClick = {
                                if (state.listing != listing) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                viewModel.setListing(listing)
                            },
                            label = { Text(listing.label) },
                        )
                    }
                }
            }
            val empty = state.books.isEmpty() && state.folders.isEmpty()
            when {
                empty && state.error != null -> MessageState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load ${state.name.ifBlank { "this catalog" }}",
                    message = state.error,
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                )
                empty && !state.loading -> MessageState(
                    icon = Icons.Outlined.SearchOff,
                    title = "Nothing here",
                    message = state.query?.let { "No books found for “$it”." },
                )
                empty -> LoadingState()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = grid,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.folders, key = { "f:" + it.url }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                        FolderRow(folder, onClick = { onOpenFolder(folder.url, folder.title) })
                    }
                    items(state.books, key = { "b:" + it.key }) { book ->
                        OnlineBookTile(book, viewModel.headers(book.coverUrl), onClick = { onOpenBook(book) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                                state.error != null -> InlineError(state.error!!, onRetry = viewModel::retry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: CatalogItem.Folder, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            folder.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BookSearchScreen(initialQuery: String, onBack: () -> Unit, onOpenBook: (OnlineBook) -> Unit, onOpenCatalog: (String) -> Unit) {
    val viewModel = discoverViewModel(key = "book-search-$initialQuery") { BookSearchViewModel(it, initialQuery) }
    val results by viewModel.results.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                title = { SearchField(query, onChange = { query = it }, onSearch = { viewModel.search(query) }, placeholder = "Search free books") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(results, key = { it.sourceId }) { result ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCatalog(result.sourceId) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(result.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open ${result.name}")
                    }
                    Spacer(Modifier.height(8.dp))
                    val shelf = result.shelf
                    when {
                        shelf.loading -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp))
                        shelf.error != null -> Text(shelf.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        shelf.items.isEmpty() -> Text("No results", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        else -> BookRow(shelf.items, viewModel::headers, onOpenBook)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineBookScreen(sourceId: String, key: String, onBack: () -> Unit, onRead: (Book) -> Unit) {
    val viewModel = discoverViewModel(key = "online-book-$sourceId-$key") { OnlineBookViewModel(it, sourceId, key) }
    val book by viewModel.book.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var wasDownloading by remember { mutableStateOf(false) }

    // A gentle tick when a download started here lands in the library.
    LaunchedEffect(download) {
        when (download) {
            is BookDownloadStatus.Running, BookDownloadStatus.Queued -> wasDownloading = true
            is BookDownloadStatus.Done -> if (wasDownloading) {
                wasDownloading = false
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val current = book
        if (current == null || current.title.isBlank()) {
            if (error != null) {
                MessageState(Icons.Outlined.CloudOff, "Couldn't load this book", error, Modifier.padding(padding), actionLabel = "Retry", onAction = viewModel::loadDetails)
            } else {
                LoadingState(Modifier.padding(padding))
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row {
                RemoteCover(current.coverUrl, viewModel.headers(current.coverUrl), current.title, Modifier.width(118.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    current.author?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    listOfNotNull(current.language, current.rights).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.animateContentSize()) {
                DownloadArea(
                    book = current,
                    loading = loading,
                    inLibrary = inLibrary,
                    status = download,
                    onDownload = { file ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.download(file)
                    },
                    onCancel = viewModel::cancel,
                    onRead = { libraryBook -> onRead(libraryBook) },
                    onReadDownloaded = { id -> scope.launch { viewModel.libraryBook(id)?.let(onRead) } },
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                InlineError(it, onRetry = viewModel::loadDetails)
            }
            current.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(18.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
                )
            }
            if (current.subjects.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Library headings read "England -- Fiction"; show them as "England · Fiction".
                    current.subjects.take(10).forEach { subject ->
                        AssistChip(onClick = {}, label = { Text(subject.replace(" -- ", " · "), style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DownloadArea(
    book: OnlineBook,
    loading: Boolean,
    inLibrary: Book?,
    status: BookDownloadStatus,
    onDownload: (BookFile) -> Unit,
    onCancel: () -> Unit,
    onRead: (Book) -> Unit,
    onReadDownloaded: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            inLibrary != null -> {
                Button(onClick = { onRead(inLibrary) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Read")
                }
                Spacer(Modifier.height(6.dp))
                Text("In your library, ready to read offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status is BookDownloadStatus.Done -> Button(onClick = { onReadDownloaded(status.bookId) }, modifier = Modifier.fillMaxWidth()) { Text("Read") }
            status is BookDownloadStatus.Running || status == BookDownloadStatus.Queued -> {
                val fraction = (status as? BookDownloadStatus.Running)?.fraction
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                status == BookDownloadStatus.Queued -> "Waiting for a connection"
                                fraction != null -> "Downloading ${(fraction * 100).roundToInt()}%"
                                else -> "Downloading"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            book.files.isNotEmpty() -> {
                if (status is BookDownloadStatus.Failed) {
                    Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                book.files.forEachIndexed { index, file ->
                    val label = listOfNotNull(downloadLabel(file, book.files.size), file.sizeBytes?.let(::formatSize)).joinToString(" · ")
                    if (index == 0) {
                        Button(onClick = { onDownload(file) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { onDownload(file) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
            loading || !book.complete -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { CircularProgressIndicator(Modifier.size(24.dp)) }
            else -> Text(
                "This catalog doesn't offer a free EPUB or PDF of this book.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun downloadLabel(file: BookFile, count: Int): String {
    val format = file.format.name
    val variant = file.label?.takeIf { count > 1 }
    return if (variant != null) "Download $format, ${variant.replaceFirstChar { it.lowercase(Locale.getDefault()) }}" else "Download $format"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

@Composable
fun CatalogEditScreen(sourceId: String?, onDone: () -> Unit) {
    val viewModel = discoverViewModel(key = "catalog-edit-${sourceId ?: "new"}") { CatalogEditViewModel(it, sourceId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit catalog" else "Add a catalog") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Connect an OPDS catalog: your own Calibre, Kavita or Komga server, or a library you have an account with. Only books the catalog offers as free EPUB or PDF downloads can be added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(form.name, { v -> viewModel.update { it.copy(name = v) } }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                form.url,
                { v -> viewModel.update { it.copy(url = v) } },
                label = { Text("Catalog address") },
                placeholder = { Text("http://192.168.1.20:8083/opds") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = if (viewModel.isInsecure(form.url)) {
                    { Text("This address isn't HTTPS and isn't on your home network, so your password could be read by others.", color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(form.username, { v -> viewModel.update { it.copy(username = v) } }, label = { Text("Username (if needed)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (form.username.isNotBlank()) {
                OutlinedTextField(
                    form.password,
                    { v -> viewModel.update { it.copy(password = v) } },
                    label = { Text("Password") },
                    placeholder = if (form.hasSavedPassword) ({ Text("Saved. Leave empty to keep it") }) else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Stored encrypted on this phone only, and sent only to this catalog.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { viewModel.save(onDone) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                if (form.busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Test & save")
            }
            if (viewModel.isEditing) {
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove catalog", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${form.name.ifBlank { "this catalog" }}?") },
            text = { Text("Books you downloaded from it stay in your library.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.remove(onDone) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
