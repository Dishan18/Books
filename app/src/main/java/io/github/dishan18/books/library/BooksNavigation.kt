package io.github.dishan18.books.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.dishan18.books.app
import io.github.dishan18.books.data.Book
import io.github.dishan18.books.discover.ui.BookCatalogScreen
import io.github.dishan18.books.discover.ui.BookSearchScreen
import io.github.dishan18.books.discover.ui.CatalogEditScreen
import io.github.dishan18.books.discover.ui.DiscoverScreen
import io.github.dishan18.books.discover.ui.OnlineBookScreen
import io.github.dishan18.books.ui.NavTransitions
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute

@Serializable object DiscoverRoute

@Serializable data class BookCatalogRoute(val sourceId: String, val folderUrl: String? = null, val title: String? = null)

@Serializable data class BookSearchRoute(val query: String)

@Serializable data class OnlineBookRoute(val sourceId: String, val key: String)

@Serializable data class CatalogEditRoute(val sourceId: String? = null)

/**
 * The Books tab. The library shows on its own until Discover is first opened; only then is the
 * navigation host built, so launching the app costs no more than it did before Discover existed.
 * Scroll state lives here, so the library doesn't move when the host takes over.
 */
@Composable
fun BooksTab(nav: NavHostController, libraryViewModel: LibraryViewModel, onOpenBook: (Book, Rect) -> Unit) {
    var hostStarted by rememberSaveable { mutableStateOf(false) }
    var openDiscover by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val topBarState = rememberTopAppBarState()
    if (!hostStarted) {
        LibraryScreen(libraryViewModel, onOpen = onOpenBook, onDiscover = { hostStarted = true; openDiscover = true }, gridState, topBarState)
        return
    }
    BooksNavHost(nav, libraryViewModel, onOpenBook, gridState, topBarState)
    LaunchedEffect(openDiscover) {
        if (openDiscover) {
            openDiscover = false
            nav.navigate(DiscoverRoute)
        }
    }
}

/** The library, and online discovery behind it. */
@Composable
private fun BooksNavHost(
    nav: NavHostController,
    libraryViewModel: LibraryViewModel,
    onOpenBook: (Book, Rect) -> Unit,
    gridState: LazyGridState,
    topBarState: TopAppBarState,
) {
    // Looked up on use, so opening the library doesn't create the online modules.
    val app = LocalContext.current.app
    val openOnline: (io.github.dishan18.books.discover.catalog.OnlineBook) -> Unit = { book ->
        app.discover.sources.remember(book)
        nav.navigate(OnlineBookRoute(book.sourceId, book.key))
    }
    NavHost(
        navController = nav,
        startDestination = LibraryRoute,
        enterTransition = NavTransitions.enter,
        exitTransition = NavTransitions.exit,
        popEnterTransition = NavTransitions.popEnter,
        popExitTransition = NavTransitions.popExit,
    ) {
        composable<LibraryRoute> {
            LibraryScreen(libraryViewModel, onOpen = onOpenBook, onDiscover = { nav.navigate(DiscoverRoute) }, gridState, topBarState)
        }
        composable<DiscoverRoute> {
            DiscoverScreen(
                onBack = { nav.popBackStack() },
                onOpenCatalog = { nav.navigate(BookCatalogRoute(it)) },
                onSearch = { nav.navigate(BookSearchRoute(it)) },
                onOpenBook = openOnline,
                onAddCatalog = { nav.navigate(CatalogEditRoute()) },
                onEditCatalog = { nav.navigate(CatalogEditRoute(it)) },
            )
        }
        composable<BookCatalogRoute> { entry ->
            val route = entry.toRoute<BookCatalogRoute>()
            BookCatalogScreen(
                sourceId = route.sourceId,
                folderUrl = route.folderUrl,
                folderTitle = route.title,
                onBack = { nav.popBackStack() },
                onOpenFolder = { url, title -> nav.navigate(BookCatalogRoute(route.sourceId, url, title)) },
                onOpenBook = openOnline,
            )
        }
        composable<BookSearchRoute> { entry ->
            BookSearchScreen(
                initialQuery = entry.toRoute<BookSearchRoute>().query,
                onBack = { nav.popBackStack() },
                onOpenBook = openOnline,
                onOpenCatalog = { nav.navigate(BookCatalogRoute(it)) },
            )
        }
        composable<OnlineBookRoute> { entry ->
            val route = entry.toRoute<OnlineBookRoute>()
            OnlineBookScreen(route.sourceId, route.key, onBack = { nav.popBackStack() }, onRead = { onOpenBook(it, Rect.Zero) })
        }
        composable<CatalogEditRoute> { entry ->
            CatalogEditScreen(entry.toRoute<CatalogEditRoute>().sourceId, onDone = { nav.popBackStack() })
        }
    }
}
