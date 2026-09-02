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
 *  - v7: `edit_queue` rebuilt on a `pageId` primary key; `forceWrite`
 *    added (Batch 26e / B-41 + B-46 + R-28)
 *  - v8: `pages (collectiveId, serverTimestamp)` index added (R-50)
 *  - v9: `collectives.circleId` / `.level` / `.userShowMembers`
 *    added (B-83)
 *  - v10: `edit_queue.attempts` / `attachments.attempts` added (issue #30)
 *
 * Each [Migration] is verified by `MigrationTest` in `androidTest`, which
 * evolves a fresh DB through the chain and asserts the final schema
 * matches the latest JSON.
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

/**
 * B-41 + B-46 + R-28 (Batch 26e): rebuild `edit_queue` with `pageId` as
 * the primary key (drops the `id` autoincrement column), add the
 * `forceWrite` column for the "Replace with my draft" force-write path,
 * and replace the unique `pageId` index with a composite
 * `(status, queuedAt)` index covering the `pendingEntries` query.
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `edit_queue_new` (" +
                "`pageId` INTEGER NOT NULL, " +
                "`baseEtag` TEXT, " +
                "`newBodyMd` TEXT NOT NULL, " +
                "`queuedAt` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`forceWrite` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`pageId`))",
        )
        db.execSQL(
            "INSERT INTO `edit_queue_new` (`pageId`, `baseEtag`, `newBodyMd`, `queuedAt`, `status`, `forceWrite`) " +
                "SELECT `pageId`, `baseEtag`, `newBodyMd`, `queuedAt`, `status`, 0 FROM `edit_queue`",
        )
        db.execSQL("DROP TABLE `edit_queue`")
        db.execSQL("ALTER TABLE `edit_queue_new` RENAME TO `edit_queue`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_edit_queue_status_queuedAt` " +
                "ON `edit_queue` (`status`, `queuedAt`)",
        )
    }
}

/**
 * R-50: index covering `PageDao.observeRecentInCollective`, which filters on
 * `collectiveId` and orders by `serverTimestamp DESC`. The three older
 * `pages` indices all stop short of `serverTimestamp`, so without this one
 * SQLite sorts the filtered rows into a transient B-tree on every emission.
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pages_collectiveId_serverTimestamp` " +
                "ON `pages` (`collectiveId`, `serverTimestamp`)",
        )
    }
}

/**
 * B-83: the collectives payload has always carried `circleId`, `level` and
 * `userShowMembers`, and none of them were kept. Without `circleId` the app
 * cannot address the Circles API — which is where membership lives, because
 * Collectives has no members endpoint — and without `level` it cannot tell
 * an owner from a member.
 *
 * All three arrive as plain `ADD COLUMN`s, so pre-v9 rows keep every value
 * they had. `circleId` is nullable, which is the honest reading of a row
 * cached before the column existed. The two NOT NULL columns take the
 * defaults `CollectiveDto` uses for an absent field: level 0 ("the server
 * didn't say", never a real level) and `userShowMembers` 1 (a missing
 * display preference is not an opt-out). Both are corrected by the next
 * `refresh()`, which runs on app open.
 */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `collectives` ADD COLUMN `circleId` TEXT")
        db.execSQL("ALTER TABLE `collectives` ADD COLUMN `level` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `collectives` ADD COLUMN `userShowMembers` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Issue #30: give each queued row its own retry budget.
 *
 * Both workers advertised a ten-attempt cap and were reading
 * `CoroutineWorker.runAttemptCount`, which counts runs of the *WorkRequest*.
 * Each worker re-queries every pending row on every run, and both enqueue
 * with `APPEND_OR_REPLACE`, so a row that joined the database while an older
 * request was in high-count backoff met a classifier that already saw attempt
 * 10 — and its first retryable failure was settled as terminal. For an edit
 * that meant parked as `CONFLICTED`; for an upload, `FAILED`. Neither status
 * is selected again, so the newer request could not rescue it.
 *
 * Plain `ADD COLUMN`s defaulting to 0, so every row already queued starts
 * with a full budget — which is the right answer for rows that may have been
 * failed early by exactly the bug this fixes.
 */
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `edit_queue` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `attachments` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
    }
}

internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
)
