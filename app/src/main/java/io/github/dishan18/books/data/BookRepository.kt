package io.github.dishan18.books.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.coverFitting
import java.io.File
import java.util.UUID

class BookRepository(
    private val context: Context,
    private val db: BooksDatabase,
    private val readium: Readium,
) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    val books = db.books().observeAll()

    fun fileOf(book: Book) = File(booksDir, book.fileName)
    fun coverOf(book: Book) = File(coversDir, "${book.id}.jpg")

    suspend fun get(id: String) = db.books().get(id)

    fun bookmarks(bookId: String) = db.bookmarks().observeForBook(bookId)

    suspend fun addBookmark(bookmark: Bookmark) = db.bookmarks().insert(bookmark)
    suspend fun deleteBookmark(bookmark: Bookmark) = db.bookmarks().delete(bookmark)

    suspend fun saveLocation(bookId: String, location: String, progress: Float) =
        db.books().updateLocation(bookId, location, progress.coerceIn(0f, 1f))

    suspend fun markOpened(bookId: String) = db.books().markOpened(bookId, System.currentTimeMillis())

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        db.books().delete(book)
        fileOf(book).delete()
        coverOf(book).delete()
    }

    /** Copies the document at [uri] into app storage and adds it to the library. */
    suspend fun import(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        val displayName = displayNameOf(uri)
        val format = formatOf(uri, displayName)
            ?: return@withContext Result.failure(IllegalArgumentException("$displayName isn't a PDF or EPUB"))

        val id = UUID.randomUUID().toString()
        val file = File(booksDir, "$id.${format.name.lowercase()}")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val fallbackTitle = displayName.substringBeforeLast('.').replace('_', ' ').trim()
            val book = when (format) {
                BookFormat.PDF -> importPdf(id, file, fallbackTitle)
                BookFormat.EPUB -> importEpub(id, file, fallbackTitle)
            }
            db.books().insert(book)
            Result.success(book)
        } catch (e: Exception) {
            file.delete()
            File(coversDir, "$id.jpg").delete()
            Result.failure(e)
        }
    }

    private fun importPdf(id: String, file: File, title: String): Book {
        val hasCover = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) error("This PDF has no pages")
                renderer.openPage(0).use { page ->
                    val width = COVER_WIDTH
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    saveCover(id, bitmap)
                }
            }
        }
        return Book(
            id = id,
            title = title,
            author = null,
            format = BookFormat.PDF,
            fileName = file.name,
            hasCover = hasCover,
            addedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun importEpub(id: String, file: File, fallbackTitle: String): Book {
        val publication = readium.open(file).getOrThrow()
        try {
            val cover = publication.coverFitting(Size(COVER_WIDTH, COVER_WIDTH * 3 / 2))
            return Book(
                id = id,
                title = publication.metadata.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                author = publication.metadata.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                format = BookFormat.EPUB,
                fileName = file.name,
                hasCover = cover != null && saveCover(id, cover),
                addedAt = System.currentTimeMillis(),
            )
        } finally {
            publication.close()
        }
    }

    private fun saveCover(id: String, bitmap: Bitmap): Boolean =
        File(coversDir, "$id.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

    private fun displayNameOf(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Untitled"

    private fun formatOf(uri: Uri, displayName: String): BookFormat? {
        val mime = context.contentResolver.getType(uri)
        return when {
            mime == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
            mime == "application/epub+zip" || displayName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> null
        }
    }

    private companion object {
        const val COVER_WIDTH = 600
    }
}
