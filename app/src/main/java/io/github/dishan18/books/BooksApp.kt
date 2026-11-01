package io.github.dishan18.books

import android.app.Application
import android.content.Context
import io.github.dishan18.books.data.BookRepository
import io.github.dishan18.books.data.BooksDatabase
import io.github.dishan18.books.data.Readium
import io.github.dishan18.books.data.ReaderSettingsStore

class BooksApp : Application() {
    val readium by lazy { Readium(this) }
    val repository by lazy { BookRepository(this, BooksDatabase.create(this), readium) }
    val readerSettings by lazy { ReaderSettingsStore(this) }
}

val Context.app: BooksApp get() = applicationContext as BooksApp
