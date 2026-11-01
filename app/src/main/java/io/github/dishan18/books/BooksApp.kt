package io.github.dishan18.books

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.data.Readium
import io.github.dishan18.books.data.ReaderSettingsStore
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.rewards.RewardsRepository
import okio.Path.Companion.toOkioPath

class BooksApp : Application(), SingletonImageLoader.Factory {
    val database by lazy { BooksDatabase.create(this) }
    val readium by lazy { Readium(this) }
    val repository by lazy { BookRepository(this, database, readium) }
    val readerSettings by lazy { ReaderSettingsStore(this) }
    val manga by lazy { MangaModule(this, database) }
    val rewards by lazy { RewardsRepository(this, database.rewards()) }

    /** Coil loads manga covers through the app's shared HTTP client and its own disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { manga.network.base })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}

val Context.app: BooksApp get() = applicationContext as BooksApp
