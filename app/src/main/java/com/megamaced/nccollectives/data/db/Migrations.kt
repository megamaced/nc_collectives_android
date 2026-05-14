package com.megamaced.nccollectives.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real Room migrations replacing the destructive fallback shipped through
 * Batches 8 / 12 / 18j. Each migration applies the schema delta captured
 * in `app/schemas/<version>.json` — the JSON files are the source of
 * truth for what the SQL below must produce.
 *
 * Version trail:
 *  - v1: initial `collectives` + `pages` (Batch 4)
 *  - v2: `pages.bodyEtag` added (Batch 7)
 *  - v3: `pages.draftBodyMd` added; `edit_queue` introduced (Batch 8)
 *  - v4: `attachments` introduced (Batch 12)
 *  - v5: `attachments.serverAttachmentId` added (Batch 18j)
 *  - v6: indexes for hot reads added (Batch 18m / R-11)
 *
 * Each [Migration] is verified by `NcCollectivesDatabaseMigrationTest`
 * which evolves a fresh DB through the chain and asserts the final
 * schema matches the v6 JSON.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pages` ADD COLUMN `bodyEtag` TEXT")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pages` ADD COLUMN `draftBodyMd` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `edit_queue` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`pageId` INTEGER NOT NULL, " +
                "`baseEtag` TEXT, " +
                "`newBodyMd` TEXT NOT NULL, " +
                "`queuedAt` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_edit_queue_pageId` ON `edit_queue` (`pageId`)",
        )
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`id` TEXT NOT NULL, " +
                "`pageId` INTEGER NOT NULL, " +
                "`fileName` TEXT NOT NULL, " +
                "`contentType` TEXT, " +
                "`size` INTEGER NOT NULL, " +
                "`lastModifiedMs` INTEGER NOT NULL, " +
                "`etag` TEXT, " +
                "`status` TEXT NOT NULL, " +
                "`localUriString` TEXT, " +
                "`lastSyncedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_pageId` ON `attachments` (`pageId`)",
        )
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `attachments` ADD COLUMN `serverAttachmentId` INTEGER")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // R-11: cover the two hot queries the audit flagged.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pages_collectiveId_title` " +
                "ON `pages` (`collectiveId`, `title`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_status` " +
                "ON `attachments` (`status`)",
        )
    }
}

internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
)
