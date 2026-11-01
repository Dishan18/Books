package io.github.dishan18.books.manga.ui.series

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.manga.data.DownloadState
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaDownload
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.ui.common.LoadingState
import io.github.dishan18.books.manga.ui.common.MangaCover
import io.github.dishan18.books.manga.ui.common.MessageState
import io.github.dishan18.books.manga.ui.common.mangaViewModel
import io.github.dishan18.books.manga.ui.common.relativeTime

@Composable
fun SeriesScreen(seriesId: Long, onBack: () -> Unit, onOpenReader: (Long) -> Unit) {
    val viewModel = mangaViewModel(key = "series-$seriesId") { SeriesViewModel(it, seriesId) }
    val series by viewModel.series.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MangaChapter?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotifications = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    series?.webUrl?.let { url ->
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in browser")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (viewModel.isOnlineSource) {
                            DropdownMenuItem(
                                text = { Text("Download unread") },
                                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                onClick = { menuOpen = false; askNotifications(); viewModel.downloadUnread() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete downloads") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menuOpen = false; viewModel.removeAllDownloads() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Mark all as read") },
                            leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                            onClick = { menuOpen = false; viewModel.setRead(chapters.orEmpty(), true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark all as unread") },
                            leadingIcon = { Icon(Icons.Outlined.RemoveDone, null) },
                            onClick = { menuOpen = false; viewModel.setRead(chapters.orEmpty(), false) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val current = series
        if (current == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            val list = chapters.orEmpty().let { if (newestFirst) it.reversed() else it }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "header") {
                    SeriesHeader(
                        series = current,
                        hasChapters = list.isNotEmpty(),
                        anyRead = chapters.orEmpty().any { it.read || it.lastPageRead > 0 },
                        onToggleFavorite = viewModel::toggleFavorite,
                        onContinue = { viewModel.continueReading(onOpenReader) },
                    )
                }
                when {
                    chapters == null || (list.isEmpty() && refreshing) -> item { LoadingState(Modifier.height(200.dp)) }
                    list.isEmpty() && error != null -> item {
                        MessageState(
                            icon = Icons.Outlined.CloudOff,
                            title = "Couldn't load chapters",
                            message = error,
                            actionLabel = "Retry",
                            onAction = viewModel::refresh,
                            modifier = Modifier.height(320.dp),
                        )
                    }
                    list.isEmpty() -> item {
                        Text(
                            "No readable chapters here yet. Chapters may be in other languages (change them in Browse → Manage sources), or only available from the official publisher.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    else -> {
                        item(key = "chapters-header") {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${list.size} chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { newestFirst = !newestFirst }) {
                                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = if (newestFirst) "Oldest first" else "Newest first")
                                }
                            }
                        }
                        items(list, key = { it.id }) { chapter ->
                            ChapterRow(
                                chapter = chapter,
                                download = downloads[chapter.id],
                                canDownload = viewModel.isOnlineSource,
                                onOpen = { onOpenReader(chapter.id) },
                                onLongPress = { actionsFor = chapter },
                                onDownload = { askNotifications(); viewModel.download(listOf(chapter)) },
                                onRetry = { viewModel.retryOrResume(chapter) },
                                onPause = { viewModel.pause(chapter) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { chapter ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Text(chapter.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
                headlineContent = { Text(if (chapter.read) "Mark as unread" else "Mark as read") },
                leadingContent = { Icon(if (chapter.read) Icons.Outlined.RemoveDone else Icons.Outlined.Done, null) },
                colors = transparent,
                modifier = Modifier.clickable { viewModel.setRead(listOf(chapter), !chapter.read); actionsFor = null },
            )
            ListItem(
                headlineContent = { Text("Mark previous as read") },
                leadingContent = { Icon(Icons.Outlined.DoneAll, null) },
                colors = transparent,
                modifier = Modifier.clickable { viewModel.markPreviousRead(chapter); actionsFor = null },
            )
            if (viewModel.isOnlineSource) {
                val downloaded = downloads[chapter.id] != null
                ListItem(
                    headlineContent = { Text(if (downloaded) "Delete download" else "Download") },
                    leadingContent = { Icon(if (downloaded) Icons.Outlined.Delete else Icons.Outlined.Download, null) },
                    colors = transparent,
                    modifier = Modifier.clickable {
                        if (downloaded) viewModel.removeDownload(listOf(chapter)) else { askNotifications(); viewModel.download(listOf(chapter)) }
                        actionsFor = null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesHeader(
    series: MangaSeries,
    hasChapters: Boolean,
    anyRead: Boolean,
    onToggleFavorite: () -> Unit,
    onContinue: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row {
            MangaCover(series.coverUrl, series.sourceId, series.title, Modifier.width(118.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(series.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                listOfNotNull(series.author, series.artist?.takeIf { it != series.author }).joinToString(", ").takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                statusLabel(series.status)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onContinue, enabled = hasChapters, modifier = Modifier.weight(1f)) {
                Text(if (anyRead) "Continue reading" else "Start reading")
            }
            FilledTonalButton(onClick = onToggleFavorite) {
                Icon(if (series.favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (series.favorite) "In library" else "Add to library")
            }
        }
        series.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(14.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
            )
        }
        val genres = series.genreList
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                genres.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun statusLabel(status: SeriesStatus) = when (status) {
    SeriesStatus.ONGOING -> "Ongoing"
    SeriesStatus.COMPLETED -> "Completed"
    SeriesStatus.HIATUS -> "On hiatus"
    SeriesStatus.CANCELLED -> "Cancelled"
    SeriesStatus.UNKNOWN -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: MangaChapter,
    download: MangaDownload?,
    canDownload: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val faded = if (chapter.read) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.Medium,
                color = faded,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val inProgress = !chapter.read && chapter.lastPageRead > 0
            val details = listOfNotNull(
                chapter.uploadedAt?.let { relativeTime(it) },
                chapter.scanlator,
                if (inProgress) "Page ${chapter.lastPageRead + 1}${chapter.pageCount?.let { " of $it" } ?: ""}" else null,
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inProgress) MaterialTheme.colorScheme.primary else faded.copy(alpha = faded.alpha * 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canDownload) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when (download?.state) {
                    null -> IconButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DownloadState.QUEUED -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Schedule, contentDescription = "Queued, tap to pause", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DownloadState.DOWNLOADING -> IconButton(onClick = onPause) {
                        if (download.totalPages > 0) {
                            CircularProgressIndicator(progress = { download.downloadedPages.toFloat() / download.totalPages }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        } else {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        }
                    }
                    DownloadState.PAUSED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.PauseCircle, contentDescription = "Paused, tap to resume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DownloadState.FAILED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = "Download failed, tap to retry", tint = MaterialTheme.colorScheme.error)
                    }
                    // Deleting is one tap away in the actions sheet, never a single accidental tap.
                    DownloadState.COMPLETED -> IconButton(onClick = onLongPress) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
