package io.github.dishan18.books.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookFormat { PDF, EPUB }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    /** File name inside the app's private `books/` directory. */
    val fileName: String,
    val hasCover: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    /** Reading progress through the whole book, from 0 to 1. */
    val progress: Float = 0f,
    /** Where to resume: a page index for PDFs, a Readium locator JSON for EPUBs. */
    val location: String? = null,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** Same encoding as [Book.location]. */
    val location: String,
    val label: String,
    val progress: Float,
    val createdAt: Long,
)
