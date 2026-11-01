package io.github.dishan18.books.manga.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.manga.data.DownloadItem
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.ui.common.LoadingState
import io.github.dishan18.books.manga.ui.common.MessageState

@Composable
fun DownloadsTab(viewModel: DownloadsViewModel, onRequestNotifications: () -> Unit) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = queue

    LaunchedEffect(items?.isNotEmpty()) { if (items?.isNotEmpty() == true) onRequestNotifications() }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Chapters at a time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { n ->
                        FilterChip(
                            selected = settings.chapterConcurrency == n,
                            onClick = { viewModel.updateSettings(settings.copy(chapterConcurrency = n)) },
                            label = { Text("$n") },
                        )
                    }
                }
            }
            ListItem(
                headlineContent = { Text("Download on Wi‑Fi only") },
                supportingContent = { Text("Applies to downloads started from now on") },
                trailingContent = {
                    Switch(checked = settings.wifiOnly, onCheckedChange = { viewModel.updateSettings(settings.copy(wifiOnly = it)) })
                },
            )
        }
        when {
            items == null -> item { LoadingState(Modifier.height(240.dp)) }
            items.isEmpty() -> item {
                MessageState(
                    icon = Icons.Outlined.Download,
                    title = "No downloads in progress",
                    message = "Download chapters from a series page to read them offline. Finished downloads stay with their series.",
                    modifier = Modifier.height(360.dp),
                )
            }
            else -> items(items, key = { it.download.chapterId }) { item ->
                DownloadRow(item, viewModel, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, viewModel: DownloadsViewModel, modifier: Modifier = Modifier) {
    val download = item.download
    ListItem(
        modifier = modifier,
        headlineContent = { Text(item.seriesTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(item.chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                when (download.state) {
                    DownloadState.DOWNLOADING -> if (download.totalPages > 0) {
                        LinearProgressIndicator(
                            progress = { download.downloadedPages.toFloat() / download.totalPages },
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                    }
                    DownloadState.QUEUED -> Text("Waiting", style = MaterialTheme.typography.labelSmall)
                    DownloadState.PAUSED -> Text(
                        if (download.totalPages > 0) "Paused · ${download.downloadedPages}/${download.totalPages}" else "Paused",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    DownloadState.FAILED -> Text(
                        download.error ?: "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                    DownloadState.COMPLETED -> Unit
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (download.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> IconButton(onClick = { viewModel.pause(item) }) {
                        Icon(Icons.Outlined.Pause, contentDescription = "Pause")
                    }
                    DownloadState.PAUSED -> IconButton(onClick = { viewModel.resume(item) }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume")
                    }
                    DownloadState.FAILED -> IconButton(onClick = { viewModel.resume(item) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Retry")
                    }
                    DownloadState.COMPLETED -> Unit
                }
                IconButton(onClick = { viewModel.cancel(item) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cancel download")
                }
            }
        },
    )
}
