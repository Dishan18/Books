package io.github.dishan18.books.manga.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.ui.common.MessageState
import io.github.dishan18.books.manga.ui.common.SeriesTile
import io.github.dishan18.books.manga.ui.common.mangaViewModel

@Composable
fun CatalogScreen(sourceId: String, onBack: () -> Unit, onOpenSeries: (Long) -> Unit) {
    val viewModel = mangaViewModel(key = "catalog-$sourceId") { CatalogViewModel(it, sourceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()

    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 8 } ?: false } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) viewModel.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    if (searching) {
                        SearchField(query, onChange = { query = it }, onSearch = { viewModel.search(query) }, placeholder = "Search ${state.sourceName}")
                    } else {
                        Text(state.sourceName)
                    }
                },
                actions = {
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
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.supportsLatest && state.query == null) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.listing == Listing.POPULAR, onClick = { viewModel.setListing(Listing.POPULAR) }, label = { Text("Popular") })
                    FilterChip(selected = state.listing == Listing.LATEST, onClick = { viewModel.setListing(Listing.LATEST) }, label = { Text("Latest") })
                }
            }
            when {
                state.items.isEmpty() && state.error != null -> MessageState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load ${state.sourceName.ifBlank { "this source" }}",
                    message = state.error,
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                )
                state.items.isEmpty() && !state.loading && !state.hasNext -> MessageState(
                    icon = Icons.Outlined.SearchOff,
                    title = "Nothing found",
                    message = state.query?.let { "No results for “$it”." },
                )
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = grid,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.key }) { _, item ->
                        SeriesTile(
                            title = item.title,
                            coverUrl = item.coverUrl,
                            sourceId = sourceId,
                            subtitle = item.author,
                            onClick = { viewModel.open(item, onOpenSeries) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                                state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = viewModel::retry) { Text("Retry") }
                                }
                            }
                        }
                    }
                }
            }
        }
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
fun GlobalSearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val viewModel = mangaViewModel(key = "global-$initialQuery") { GlobalSearchViewModel(it, initialQuery) }
    val results by viewModel.results.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                title = { SearchField(query, onChange = { query = it }, onSearch = { viewModel.search(query) }, placeholder = "Search all sources") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(results, key = { it.sourceId }) { result ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSource(result.sourceId) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(result.sourceName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open ${result.sourceName}")
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        result.loading -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp))
                        result.error != null -> Text(
                            result.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        result.items.isEmpty() -> Text(
                            "No results",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(result.items, key = { it.key }) { item ->
                                SeriesTile(
                                    title = item.title,
                                    coverUrl = item.coverUrl,
                                    sourceId = result.sourceId,
                                    subtitle = item.author,
                                    onClick = { viewModel.open(result.sourceId, item, onOpenSeries) },
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
