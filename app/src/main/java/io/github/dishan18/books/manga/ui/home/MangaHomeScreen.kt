package io.github.dishan18.books.manga.ui.home

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dishan18.books.manga.ui.common.mangaViewModel
import kotlinx.coroutines.launch

enum class MangaSection(val label: String) { LIBRARY("Library"), HISTORY("History"), BROWSE("Browse"), DOWNLOADS("Downloads") }

@Composable
fun MangaHomeScreen(
    onOpenSeries: (Long) -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenSource: (String) -> Unit,
    onGlobalSearch: (String) -> Unit,
    onManageSources: () -> Unit,
) {
    val libraryViewModel = mangaViewModel { MangaLibraryViewModel(it) }
    val downloadsViewModel = mangaViewModel { DownloadsViewModel(it) }
    val pager = rememberPagerState { MangaSection.entries.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val section = MangaSection.entries[pager.currentPage]
    var pendingArchives by remember { mutableStateOf<List<Uri>?>(null) }
    var importTitle by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importTitle = libraryViewModel.suggestTitle(uris)
            pendingArchives = uris
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(libraryViewModel::importFolder)
    }
    // Download progress and new-chapter notifications are optional; ask once, when it matters.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        libraryViewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Manga", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        when (section) {
                            MangaSection.LIBRARY -> {
                                IconButton(onClick = libraryViewModel::refresh) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "Check for new chapters")
                                }
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Import CBZ / ZIP files") },
                                        leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                                        onClick = {
                                            menuOpen = false
                                            archivePicker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream"))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import image folder") },
                                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                        onClick = {
                                            menuOpen = false
                                            folderPicker.launch(null)
                                        },
                                    )
                                }
                            }
                            MangaSection.BROWSE -> IconButton(onClick = onManageSources) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Manage sources")
                            }
                            MangaSection.DOWNLOADS -> {
                                IconButton(onClick = downloadsViewModel::resumeAll) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume all")
                                }
                                IconButton(onClick = downloadsViewModel::pauseAll) {
                                    Icon(Icons.Outlined.Pause, contentDescription = "Pause all")
                                }
                            }
                            MangaSection.HISTORY -> Unit
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
                    MangaSection.entries.forEachIndexed { index, item ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text(item.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        HorizontalPager(pager, modifier = Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 1) { page ->
            when (MangaSection.entries[page]) {
                MangaSection.LIBRARY -> LibraryTab(
                    viewModel = libraryViewModel,
                    onOpenSeries = onOpenSeries,
                    onOpenReader = onOpenReader,
                    onBrowse = { scope.launch { pager.animateScrollToPage(MangaSection.BROWSE.ordinal) } },
                )
                MangaSection.HISTORY -> HistoryTab(libraryViewModel, onOpenSeries = onOpenSeries, onOpenReader = onOpenReader)
                MangaSection.BROWSE -> BrowseTab(onOpenSource = onOpenSource, onGlobalSearch = onGlobalSearch, onManageSources = onManageSources)
                MangaSection.DOWNLOADS -> DownloadsTab(
                    viewModel = downloadsViewModel,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
        }
    }

    pendingArchives?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingArchives = null },
            title = { Text("Import ${uris.size} file${if (uris.size == 1) "" else "s"}") },
            text = {
                Column {
                    Text("Each file becomes a chapter of this series.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = importTitle,
                        onValueChange = { importTitle = it },
                        label = { Text("Series title") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingArchives = null
                    libraryViewModel.importArchives(uris, importTitle.trim())
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingArchives = null }) { Text("Cancel") } },
        )
    }
}

