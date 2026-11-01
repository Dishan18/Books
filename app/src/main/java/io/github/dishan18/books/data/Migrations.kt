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

/** v2 → v3: adds reading-rewards tables (sessions, daily totals, badges, finished items). Additive. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, `itemId` TEXT NOT NULL, `title` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, `activeSeconds` INTEGER NOT NULL, `day` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_day` ON `reading_sessions` (`day`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_kind_itemId` ON `reading_sessions` (`kind`, `itemId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_days` (`day` INTEGER NOT NULL, `activeSeconds` INTEGER NOT NULL, " +
                "`goalMet` INTEGER NOT NULL, `frozen` INTEGER NOT NULL, `lateNight` INTEGER NOT NULL, " +
                "`earlyMorning` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `earned_badges` (`badgeId` TEXT NOT NULL, `earnedAt` INTEGER NOT NULL, " +
                "`seen` INTEGER NOT NULL, PRIMARY KEY(`badgeId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `finished_items` (`kind` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `finishedAt` INTEGER NOT NULL, `day` INTEGER NOT NULL, `pages` INTEGER NOT NULL, " +
                "PRIMARY KEY(`kind`, `itemId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finished_items_day` ON `finished_items` (`day`)")
    }
}

/**
 * v3 → v4: online book catalogs, where a downloaded book came from, book subjects (for Year in
 * Books), and pages turned per day and session. Additive: existing rows keep every value.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceId` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceKey` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `subjects` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_sourceId_sourceKey` ON `books` (`sourceId`, `sourceKey`)")
        db.execSQL("ALTER TABLE `reading_sessions` ADD COLUMN `pages` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `reading_days` ADD COLUMN `pages` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `book_sources` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, `username` TEXT, `sortOrder` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
