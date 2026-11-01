package io.github.dishan18.books.discover.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Kinds of online book catalogs. */
enum class BookSourceType { GUTENBERG, OPDS }

/** An online book catalog: Project Gutenberg, or an OPDS catalog the user added (Calibre, Kavita, Komga…). */
@Entity(tableName = "book_sources")
data class BookSourceEntity(
    @PrimaryKey val id: String,
    /** [BookSourceType] name. */
    val type: String,
    val name: String,
    /** Catalog address. Never contains secrets; those live in the credential store. */
    val url: String,
    /** Optional user name for catalogs that need a password. */
    val username: String? = null,
    val sortOrder: Int = 0,
    val addedAt: Long,
)

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_sources ORDER BY sortOrder, addedAt")
    fun observeAll(): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_sources WHERE id = :id")
    suspend fun get(id: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(source: BookSourceEntity)

    @Upsert
    suspend fun upsert(source: BookSourceEntity)

    @Query("DELETE FROM book_sources WHERE id = :id")
    suspend fun delete(id: String)
}
