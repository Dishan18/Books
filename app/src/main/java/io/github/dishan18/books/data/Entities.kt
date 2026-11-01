package io.github.dishan18.books.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookFormat { PDF, EPUB }

@Entity(tableName = "books", indices = [Index(value = ["sourceId", "sourceKey"])])
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
    /** The online catalog this book was downloaded from (see `discover`), or null if imported. */
    @ColumnInfo(defaultValue = "NULL") val sourceId: String? = null,
    /** The book's identifier inside that catalog. */
    @ColumnInfo(defaultValue = "NULL") val sourceKey: String? = null,
    /** Subjects or genres, one per line, from the EPUB or the catalog. */
    @ColumnInfo(defaultValue = "''") val subjects: String = "",
) {
    val subjectList: List<String> get() = subjects.split('\n').filter { it.isNotBlank() }
}

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
