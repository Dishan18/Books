package io.github.dishan18.books.manga.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.manga.data.HistoryEntry
import io.github.dishan18.books.manga.data.LibrarySeries
import io.github.dishan18.books.manga.ui.common.LoadingState
import io.github.dishan18.books.manga.ui.common.MangaCover
import io.github.dishan18.books.manga.ui.common.MessageState
import io.github.dishan18.books.manga.ui.common.SeriesTile
import io.github.dishan18.books.manga.ui.common.relativeTime
import io.github.dishan18.books.ui.PressableBox

@Composable
fun LibraryTab(
    viewModel: MangaLibraryViewModel,
    onOpenSeries: (Long) -> Unit,
    onOpenReader: (Long) -> Unit,
    onBrowse: () -> Unit,
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<LibrarySeries?>(null) }

    val items = library
    Column(Modifier.fillMaxSize()) {
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        PullToRefreshBox(isRefreshing = updating, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                items == null -> LoadingState()
                items.isEmpty() -> MessageState(
                    icon = Icons.Outlined.AutoStories,
                    title = "Your manga library is empty",
                    message = "Find series in Browse and add them to your library, or import CBZ/ZIP files from the menu.",
                    actionLabel = "Browse sources",
                    onAction = onBrowse,
                )
                else -> {
                    val latest = history?.firstOrNull { entry -> items.any { it.series.id == entry.seriesId } }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (latest != null) {
                            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                                ContinueCard(latest, onClick = { viewModel.resume(latest.seriesId, onOpenReader) }, modifier = Modifier.animateItem())
                            }
                        }
                        items(items, key = { it.series.id }) { entry ->
                            SeriesTile(
                                title = entry.series.title,
                                coverUrl = entry.series.coverUrl,
                                sourceId = entry.series.sourceId,
                                subtitle = when {
                                    entry.chapterCount == 0 -> "No chapters yet"
                                    entry.unreadCount == 0 -> "All read"
                                    else -> "${entry.unreadCount} unread"
                                },
                                badge = entry.unreadCount.takeIf { it > 0 }?.toString(),
                                onClick = { onOpenSeries(entry.series.id) },
                                onLongClick = { removing = entry },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${entry.series.title}”?") },
            text = { Text("It leaves your library. Reading progress and downloads are kept until you delete them.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFromLibrary(entry.series.id)
                    removing = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressableBox(onClick = { onClick() }, onLongClick = null, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover(entry.coverUrl, entry.sourceId, entry.seriesTitle, Modifier.width(70.dp).aspectRatio(2f / 3f), elevation = 3f)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(entry.seriesTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.chapterName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pages = entry.pageCount
                if (pages != null && pages > 0 && !entry.chapterRead) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (entry.lastPageRead + 1f) / pages },
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryTab(viewModel: MangaLibraryViewModel, onOpenSeries: (Long) -> Unit, onOpenReader: (Long) -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val entries = history
    when {
        entries == null -> LoadingState()
        entries.isEmpty() -> MessageState(
            icon = Icons.Outlined.History,
            title = "Nothing read yet",
            message = "Chapters you open show up here so you can jump back in.",
        )
        else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.seriesId }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.resume(entry.seriesId, onOpenReader) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                        .animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.clickable { onOpenSeries(entry.seriesId) }) {
                        MangaCover(entry.coverUrl, entry.sourceId, entry.seriesTitle, Modifier.width(52.dp).aspectRatio(2f / 3f), elevation = 2f)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.seriesTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val progress = when {
                            entry.chapterRead -> "Finished"
                            entry.pageCount != null -> "Page ${entry.lastPageRead + 1} of ${entry.pageCount}"
                            else -> null
                        }
                        Text(
                            listOfNotNull(entry.chapterName, progress).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(relativeTime(entry.readAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.clearHistory(entry.seriesId) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove from history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
