package io.github.dishan18.books

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.library.LibraryScreen
import io.github.dishan18.books.library.LibraryViewModel
import io.github.dishan18.books.manga.ui.MangaHomeRoute
import io.github.dishan18.books.manga.ui.MangaNavHost
import io.github.dishan18.books.rewards.ui.ProgressScreen

enum class AppTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    BOOKS("Books", Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    MANGA("Manga", Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
    PROGRESS("Progress", Icons.Outlined.LocalFireDepartment, Icons.Filled.LocalFireDepartment),
}

/**
 * Top level: the Books tab is the v1 library, untouched; the Manga tab hosts its own navigation.
 * The tab bar hides while a manga sub-screen (series, catalog, settings) is open.
 */
@Composable
fun AppRoot(libraryViewModel: LibraryViewModel, onOpenBook: (Book, Rect) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(AppTab.BOOKS) }
    val mangaNav = rememberNavController()
    val mangaEntry by mangaNav.currentBackStackEntryAsState()
    val mangaAtRoot = mangaEntry?.destination?.hasRoute<MangaHomeRoute>() ?: true
    val showBar = tab != AppTab.MANGA || mangaAtRoot
    val tabStates = rememberSaveableStateHolder()
    val progress by LocalContext.current.app.rewards.progress.collectAsStateWithLifecycle()
    val newBadge = progress.prefs.enabled && progress.earned.any { !it.seen }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(visible = showBar, enter = expandVertically(), exit = shrinkVertically()) {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                BadgedBox(badge = { if (item == AppTab.PROGRESS && newBadge) Badge() }) {
                                    Icon(if (tab == item) item.selectedIcon else item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.BOOKS -> LibraryScreen(libraryViewModel, onOpen = onOpenBook)
                    AppTab.MANGA -> MangaNavHost(mangaNav)
                    AppTab.PROGRESS -> ProgressScreen()
                }
            }
        }
    }
}
