package io.github.dishan18.books.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.dishan18.books.manga.data.DownloadDao
import io.github.dishan18.books.manga.data.MangaChapter
import io.github.dishan18.books.manga.data.MangaDao
import io.github.dishan18.books.manga.data.MangaDownload
import io.github.dishan18.books.manga.data.MangaHistory
import io.github.dishan18.books.manga.data.MangaSeries
import io.github.dishan18.books.manga.data.SourceConfigDao
import io.github.dishan18.books.manga.data.SourceConfigEntity
import io.github.dishan18.books.rewards.data.EarnedBadge
import io.github.dishan18.books.rewards.data.FinishedItem
import io.github.dishan18.books.rewards.data.ReadingDay
import io.github.dishan18.books.rewards.data.ReadingSession
import io.github.dishan18.books.rewards.data.RewardsDao
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): Book?

    @Insert
    suspend fun insert(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET location = :location, progress = :progress WHERE id = :id")
    suspend fun updateLocation(id: String, location: String, progress: Float)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun markOpened(id: String, time: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progress ASC")
    fun observeForBook(bookId: String): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)
}

@Database(
    entities = [
        Book::class,
        Bookmark::class,
        SourceConfigEntity::class,
        MangaSeries::class,
        MangaChapter::class,
        MangaHistory::class,
        MangaDownload::class,
        ReadingSession::class,
        ReadingDay::class,
        EarnedBadge::class,
        FinishedItem::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class BooksDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun sources(): SourceConfigDao
    abstract fun manga(): MangaDao
    abstract fun downloads(): DownloadDao
    abstract fun rewards(): RewardsDao

    companion object {
        fun create(context: Context): BooksDatabase =
            Room.databaseBuilder(context, BooksDatabase::class.java, "books.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
