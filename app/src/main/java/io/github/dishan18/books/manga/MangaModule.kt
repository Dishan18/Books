package io.github.dishan18.books.manga

import android.app.Application
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.manga.data.MangaRepository
import io.github.dishan18.books.manga.download.DownloadManager
import io.github.dishan18.books.manga.download.DownloadStorage
import io.github.dishan18.books.manga.source.CredentialStore
import io.github.dishan18.books.manga.source.SourceRegistry
import io.github.dishan18.books.manga.source.local.LocalImporter
import io.github.dishan18.books.manga.source.local.LocalSource
import io.github.dishan18.books.manga.source.net.MangaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** App-wide manga singletons, created lazily the first time the Manga tab (or reader) needs them. */
class MangaModule(val app: Application, val database: BooksDatabase) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val network = MangaNetwork(app)
    val credentials = CredentialStore(app)
    val localSource = LocalSource(File(app.filesDir, "manga-local").apply { mkdirs() })
    val sources = SourceRegistry(database.sources(), network, credentials, localSource, scope)
    val repository = MangaRepository(database.manga(), sources)
    val downloads = DownloadManager(app, database.downloads(), DownloadStorage(File(app.filesDir, "manga-downloads")))
    val importer = LocalImporter(app, localSource, repository)

    init {
        scope.launch {
            sources.ensureDefaults()
            downloads.recover()
        }
    }
}
