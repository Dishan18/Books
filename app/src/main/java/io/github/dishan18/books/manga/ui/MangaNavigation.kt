package io.github.dishan18.books.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.dishan18.books.manga.reader.WebtoonReaderActivity
import io.github.dishan18.books.manga.ui.browse.CatalogScreen
import io.github.dishan18.books.manga.ui.browse.GlobalSearchScreen
import io.github.dishan18.books.manga.ui.home.MangaHomeScreen
import io.github.dishan18.books.manga.ui.series.SeriesScreen
import io.github.dishan18.books.manga.ui.sources.KomgaEditScreen
import io.github.dishan18.books.manga.ui.sources.SourcesScreen
import io.github.dishan18.books.ui.NavTransitions
import kotlinx.serialization.Serializable

@Serializable object MangaHomeRoute

@Serializable data class CatalogRoute(val sourceId: String)

@Serializable data class GlobalSearchRoute(val query: String)

@Serializable data class SeriesRoute(val seriesId: Long)

@Serializable object SourcesRoute

@Serializable data class KomgaRoute(val sourceId: String? = null)

@Composable
fun MangaNavHost(nav: NavHostController) {
    val context = LocalContext.current
    val openReader: (Long) -> Unit = { chapterId -> context.startActivity(WebtoonReaderActivity.intent(context, chapterId)) }
    val openSeries: (Long) -> Unit = { nav.navigate(SeriesRoute(it)) }

    NavHost(
        navController = nav,
        startDestination = MangaHomeRoute,
        enterTransition = NavTransitions.enter,
        exitTransition = NavTransitions.exit,
        popEnterTransition = NavTransitions.popEnter,
        popExitTransition = NavTransitions.popExit,
    ) {
        composable<MangaHomeRoute> {
            MangaHomeScreen(
                onOpenSeries = openSeries,
                onOpenReader = openReader,
                onOpenSource = { nav.navigate(CatalogRoute(it)) },
                onGlobalSearch = { nav.navigate(GlobalSearchRoute(it)) },
                onManageSources = { nav.navigate(SourcesRoute) },
            )
        }
        composable<CatalogRoute> { entry ->
            CatalogScreen(sourceId = entry.toRoute<CatalogRoute>().sourceId, onBack = { nav.popBackStack() }, onOpenSeries = openSeries)
        }
        composable<GlobalSearchRoute> { entry ->
            GlobalSearchScreen(
                initialQuery = entry.toRoute<GlobalSearchRoute>().query,
                onBack = { nav.popBackStack() },
                onOpenSeries = openSeries,
                onOpenSource = { nav.navigate(CatalogRoute(it)) },
            )
        }
        composable<SeriesRoute> { entry ->
            SeriesScreen(seriesId = entry.toRoute<SeriesRoute>().seriesId, onBack = { nav.popBackStack() }, onOpenReader = openReader)
        }
        composable<SourcesRoute> {
            SourcesScreen(
                onBack = { nav.popBackStack() },
                onAddKomga = { nav.navigate(KomgaRoute()) },
                onEditKomga = { nav.navigate(KomgaRoute(it)) },
            )
        }
        composable<KomgaRoute> { entry ->
            KomgaEditScreen(sourceId = entry.toRoute<KomgaRoute>().sourceId, onDone = { nav.popBackStack() })
        }
    }
}
