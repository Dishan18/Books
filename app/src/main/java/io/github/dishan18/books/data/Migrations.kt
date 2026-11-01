package io.github.dishan18.books.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: adds the manga tables. Purely additive; the books tables are untouched.
 * Statements are copied from app/schemas/.../2.json so the migrated schema matches a fresh install.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manga_sources` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `settings` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `lastError` TEXT, " +
                "`lastErrorAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manga_series` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
                "`artist` TEXT, `description` TEXT, `coverUrl` TEXT, `status` TEXT NOT NULL, " +
                "`genres` TEXT NOT NULL, `webUrl` TEXT, `favorite` INTEGER NOT NULL, `favoritedAt` INTEGER, " +
                "`lastReadAt` INTEGER, `detailsUpdatedAt` INTEGER, `chaptersUpdatedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_manga_series_sourceId_sourceKey` " +
                "ON `manga_series` (`sourceId`, `sourceKey`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_series_favorite` ON `manga_series` (`favorite`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manga_chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`seriesId` INTEGER NOT NULL, `sourceKey` TEXT NOT NULL, `name` TEXT NOT NULL, `number` REAL, " +
                "`volume` TEXT, `scanlator` TEXT, `uploadedAt` INTEGER, `sortIndex` INTEGER NOT NULL, " +
                "`read` INTEGER NOT NULL, `lastPageRead` INTEGER NOT NULL, `pageOffset` REAL NOT NULL, " +
                "`pageCount` INTEGER, `readAt` INTEGER, `addedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`seriesId`) REFERENCES `manga_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_manga_chapters_seriesId_sourceKey` " +
                "ON `manga_chapters` (`seriesId`, `sourceKey`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manga_history` (`chapterId` INTEGER NOT NULL, " +
                "`seriesId` INTEGER NOT NULL, `readAt` INTEGER NOT NULL, PRIMARY KEY(`chapterId`), " +
                "FOREIGN KEY(`chapterId`) REFERENCES `manga_chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`seriesId`) REFERENCES `manga_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_history_seriesId` ON `manga_history` (`seriesId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_history_readAt` ON `manga_history` (`readAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manga_downloads` (`chapterId` INTEGER NOT NULL, " +
                "`seriesId` INTEGER NOT NULL, `state` TEXT NOT NULL, `downloadedPages` INTEGER NOT NULL, " +
                "`totalPages` INTEGER NOT NULL, `error` TEXT, `attempts` INTEGER NOT NULL, " +
                "`queuedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chapterId`), " +
                "FOREIGN KEY(`chapterId`) REFERENCES `manga_chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`seriesId`) REFERENCES `manga_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_downloads_seriesId` ON `manga_downloads` (`seriesId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_downloads_state` ON `manga_downloads` (`state`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
