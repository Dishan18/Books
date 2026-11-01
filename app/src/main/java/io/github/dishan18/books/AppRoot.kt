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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.library.BooksTab
import io.github.dishan18.books.library.LibraryRoute
import io.github.dishan18.books.library.LibraryViewModel
import io.github.dishan18.books.manga.ui.MangaHomeRoute
import io.github.dishan18.books.manga.ui.MangaNavHost
import io.github.dishan18.books.rewards.ui.ProgressScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    BOOKS("Books", Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    MANGA("Manga", Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
    PROGRESS("Progress", Icons.Outlined.LocalFireDepartment, Icons.Filled.LocalFireDepartment),
}

/**
 * Top level: the Books tab (library, with online discovery behind it), Manga and Progress. Each
 * tab keeps its own navigation; the tab bar hides while a sub-screen (a catalog, a series) is open.
 */
@Composable
fun AppRoot(libraryViewModel: LibraryViewModel, onOpenBook: (Book, Rect) -> Unit, requestedTab: MutableStateFlow<AppTab?>) {
    var tab by rememberSaveable { mutableStateOf(AppTab.BOOKS) }
    val requested by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let {
            tab = it
            requestedTab.value = null
        }
    }
    val mangaNav = rememberNavController()
    val mangaEntry by mangaNav.currentBackStackEntryAsState()
    val mangaAtRoot = mangaEntry?.destination?.hasRoute<MangaHomeRoute>() ?: true
    val booksNav = rememberNavController()
    val booksEntry by booksNav.currentBackStackEntryAsState()
    val booksAtRoot = booksEntry?.destination?.hasRoute<LibraryRoute>() ?: true
    val showBar = when (tab) {
        AppTab.BOOKS -> booksAtRoot
        AppTab.MANGA -> mangaAtRoot
        AppTab.PROGRESS -> true
    }
    val haptic = LocalHapticFeedback.current
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
                            onClick = {
                                if (tab != item) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                tab = item
                            },
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
                    AppTab.BOOKS -> BooksTab(booksNav, libraryViewModel, onOpenBook)
                    AppTab.MANGA -> MangaNavHost(mangaNav)
                    AppTab.PROGRESS -> ProgressScreen()
                }
            }
        }
    }
}
