package io.github.dishan18.books.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BooksDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsBooksAndBookmarks() = runTest {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO books (id, title, author, format, fileName, hasCover, addedAt, lastOpenedAt, progress, location) " +
                    "VALUES ('b1', 'Field Notes', 'A. Writer', 'PDF', 'b1.pdf', 1, 1000, 2000, 0.25, '3')",
            )
            execSQL(
                "INSERT INTO bookmarks (bookId, location, label, progress, createdAt) VALUES ('b1', '3', 'Page 4', 0.25, 3000)",
            )
            close()
        }

        // Validates each step against the exported schema of that version.
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, BooksDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val book = db.books().get("b1")!!
            assertEquals("Field Notes", book.title)
            assertEquals(BookFormat.PDF, book.format)
            assertEquals("3", book.location)
            assertEquals(0.25f, book.progress)
            val bookmarks = db.bookmarks().observeForBook("b1").first()
            assertEquals(listOf("Page 4"), bookmarks.map { it.label })
            assertTrue(db.manga().getFavorites().isEmpty())
            assertTrue(db.rewards().allDays().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_keepsMangaLibrary() = runTest {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO manga_series (id, sourceId, sourceKey, title, status, genres, favorite, createdAt) " +
                    "VALUES (1, 'mangadex', 'm1', 'A Webtoon', 'ONGOING', '', 1, 10)",
            )
            execSQL(
                "INSERT INTO manga_chapters (id, seriesId, sourceKey, name, sortIndex, read, lastPageRead, pageOffset, addedAt) " +
                    "VALUES (5, 1, 'c1', 'Ch. 1', 0, 1, 12, 0.5, 10)",
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, BooksDatabase::class.java, dbName).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val favorites = db.manga().getFavorites()
            assertEquals(listOf("A Webtoon"), favorites.map { it.title })
            val chapter = db.manga().getChapter(5)!!
            assertTrue(chapter.read)
            assertEquals(12, chapter.lastPageRead)
        } finally {
            db.close()
        }
    }
}
