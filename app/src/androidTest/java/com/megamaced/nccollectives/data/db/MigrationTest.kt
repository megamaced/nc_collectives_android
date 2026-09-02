package com.megamaced.nccollectives.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Throwaway database file. [MigrationTestHelper] deletes it per test. */
private const val TEST_DB = "nc-collectives-migration-test.db"

/**
 * Tracks the `version` on [NcCollectivesDatabase]. Room keeps that value in
 * generated code, so there is nothing to read it from at runtime — bump it
 * here in the same commit as the annotation.
 */
private const val LATEST_VERSION = 10

/**
 * Coverage for the hand-written [ALL_MIGRATIONS] chain. `DatabaseModule`
 * builds the database with `addMigrations` and no destructive fallback, so a
 * migration that drops the wrong table or forgets a column fails nowhere at
 * build time — it either throws on the user's next app open or quietly loses
 * queued edits and cached page bodies.
 *
 * [migrateAll] is the load-bearing test: it walks a v1 database through every
 * migration and lets Room compare the result against the committed
 * `app/schemas/…/10.json`, column by column and index by index. Schema
 * validation cannot see a *lost row*, though, so the migrations that move
 * data rather than just adding a column get their own test below.
 *
 * These are instrumented tests. They need a device or emulator
 * (`connectedDebugAndroidTest`), and the exported schema JSONs must be on the
 * androidTest assets path for [MigrationTestHelper] to load them.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NcCollectivesDatabase::class.java,
    )

    @Test
    fun allMigrationsFormAnUnbrokenChain() {
        // Catches the cheapest mistake available here: writing a migration
        // and forgetting to add it to ALL_MIGRATIONS.
        assertEquals(
            (1 until LATEST_VERSION).map { it to it + 1 },
            ALL_MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun migrateAll() {
        helper.createDatabase(TEST_DB, 1).close()

        // validateDroppedTables = true also asserts nothing extra is left
        // behind — MIGRATION_6_7's `edit_queue_new` scratch table especially.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *ALL_MIGRATIONS).close()
    }

    @Test
    fun migrateAll_keepsRowsSeededAtV1() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insertCollective(id = 7L, name = "Field Guide")
            db.insertPage(
                id = 71L,
                collectiveId = 7L,
                title = "Hedgerows",
                serverTimestamp = 1_690_000_000L,
                bodyMd = "# Hedgerows",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *ALL_MIGRATIONS)

        db.query("SELECT * FROM `collectives`").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.longAt("id"))
            assertEquals("Field Guide", cursor.stringAt("name"))
        }
        db.query("SELECT * FROM `pages`").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(71L, cursor.longAt("id"))
            assertEquals(7L, cursor.longAt("collectiveId"))
            assertEquals("Hedgerows", cursor.stringAt("title"))
            assertEquals("# Hedgerows", cursor.stringAt("bodyMd"))
            assertEquals(1_690_000_000L, cursor.longAt("serverTimestamp"))
            // MIGRATION_1_2 / MIGRATION_2_3 add these as nullable columns, so
            // a row that predates them reads back empty rather than losing
            // the body it already had.
            assertTrue(cursor.isNullAt("bodyEtag"))
            assertTrue(cursor.isNullAt("draftBodyMd"))
        }
        db.close()
    }

    /**
     * MIGRATION_8_9 adds three columns to `collectives` (B-83). Plain
     * `ADD COLUMN`s, so the risk isn't losing a row — [migrateAll] would
     * catch a botched schema — it is the *defaults* a pre-v9 row reads back
     * with. `circleId` has to be null rather than an empty string, because
     * the members path treats null as "unreachable" and would send a
     * blank path segment otherwise; `userShowMembers` has to default to 1,
     * or every collective cached before this version hides its members
     * entry point until the next refresh happens to land.
     */
    @Test
    fun migrate8To9_addsTheTeamColumnsAndKeepsCollectives() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.insertCollective(id = 7L, name = "Field Guide")
            db.insertCollective(id = 8L, name = "Seasonal Calendar")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        assertTrue(
            "MIGRATION_8_9 did not add the v9 columns",
            db.columnNames("collectives").containsAll(
                setOf("circleId", "level", "userShowMembers"),
            ),
        )
        db.query("SELECT * FROM `collectives` ORDER BY `id`").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.longAt("id"))
            assertEquals("Field Guide", cursor.stringAt("name"))
            // Unknown until the next refresh — not an empty string.
            assertTrue(cursor.isNullAt("circleId"))
            // 0 is not a level Circles ever sends; it reads as "unknown".
            assertEquals(0L, cursor.longAt("level"))
            // Shown by default: absent server-side preference is not an opt-out.
            assertEquals(1L, cursor.longAt("userShowMembers"))
            assertTrue(cursor.moveToNext())
            assertEquals(8L, cursor.longAt("id"))
            assertEquals("Seasonal Calendar", cursor.stringAt("name"))
            assertTrue(cursor.isNullAt("circleId"))
        }

        // The columns are writable at v9, i.e. the affinities are what the
        // entity expects rather than whatever the ALTER happened to produce.
        db.insertCollective(
            id = 9L,
            name = "Hedgerow Notes",
            circleId = "KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo",
            level = 9,
            userShowMembers = false,
        )
        db.query("SELECT * FROM `collectives` WHERE `id` = 9").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("KZAid9qOxZ5nfvtui2mLFKzyLRhSEoo", cursor.stringAt("circleId"))
            assertEquals(9L, cursor.longAt("level"))
            assertEquals(0L, cursor.longAt("userShowMembers"))
        }
        db.close()
    }

    /**
     * The only migration in the chain that moves data: MIGRATION_6_7 rebuilds
     * `edit_queue` onto a `pageId` primary key, which means copying every
     * queued edit into a new table and dropping the old one. A queued edit is
     * a page body the user wrote that the server has not seen yet — losing
     * one loses the only copy.
     */
    @Test
    fun migrate6To7_keepsQueuedEditsAcrossTheTableRebuild() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            val values = ContentValues().apply {
                // v6's `id` is AUTOINCREMENT, so it is left to SQLite.
                put("pageId", 4242L)
                put("baseEtag", "\"etag-9\"")
                put("newBodyMd", "# Written offline")
                put("queuedAt", 1_700_000_000_000L)
                put("status", "PENDING")
            }
            db.insert("edit_queue", SQLiteDatabase.CONFLICT_ABORT, values)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.query("SELECT * FROM `edit_queue`").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(4242L, cursor.longAt("pageId"))
            assertEquals("\"etag-9\"", cursor.stringAt("baseEtag"))
            assertEquals("# Written offline", cursor.stringAt("newBodyMd"))
            assertEquals(1_700_000_000_000L, cursor.longAt("queuedAt"))
            assertEquals("PENDING", cursor.stringAt("status"))
            // Rows that predate the column are not force-writes.
            assertEquals(0L, cursor.longAt("forceWrite"))
        }

        assertFalse(
            "the v6 autoincrement `id` column survived the rebuild",
            db.columnNames("edit_queue").contains("id"),
        )
        assertEquals(setOf("pageId"), db.primaryKeyColumns("edit_queue"))

        val indices = db.indexNames("edit_queue")
        assertFalse(indices.contains("index_edit_queue_pageId"))
        assertTrue(indices.contains("index_edit_queue_status_queuedAt"))

        // The point of the new primary key: one queued edit per page.
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO `edit_queue` " +
                    "(`pageId`, `baseEtag`, `newBodyMd`, `queuedAt`, `status`, `forceWrite`) " +
                    "VALUES (4242, NULL, '# Second edit', 1, 'PENDING', 0)",
            )
        }
        db.close()
    }

    /**
     * MIGRATION_9_10 gives each queued row its own retry budget (issue #30).
     * Two plain `ADD COLUMN`s, so as with 8→9 the risk is the *default* a
     * pre-v10 row reads back with: it has to be 0, i.e. a full budget, and
     * not whatever the ALTER happened to leave. Rows already in the queue
     * may well have been failed early by the very bug this fixes, so
     * starting them over is the point.
     */
    @Test
    fun migrate9To10_addsTheAttemptCountersWithAFullBudget() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.insert(
                "edit_queue",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("pageId", 4242L)
                    put("baseEtag", "\"etag-9\"")
                    put("newBodyMd", "# Written offline")
                    put("queuedAt", 1_700_000_000_000L)
                    put("status", "PENDING")
                    put("forceWrite", 0)
                },
            )
            db.insert(
                "attachments",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "4242/shot.png")
                    put("pageId", 4242L)
                    put("fileName", "shot.png")
                    put("contentType", "image/png")
                    put("size", 1024L)
                    put("lastModifiedMs", 1_700_000_000_000L)
                    put("status", "FAILED")
                    put("lastSyncedAt", 1_700_000_000_000L)
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        assertTrue(
            "MIGRATION_9_10 did not add edit_queue.attempts",
            db.columnNames("edit_queue").contains("attempts"),
        )
        assertTrue(
            "MIGRATION_9_10 did not add attachments.attempts",
            db.columnNames("attachments").contains("attempts"),
        )
        db.query("SELECT * FROM `edit_queue`").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("# Written offline", cursor.stringAt("newBodyMd"))
            // A full budget, not a spent one.
            assertEquals(0L, cursor.longAt("attempts"))
        }
        db.query("SELECT * FROM `attachments`").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("shot.png", cursor.stringAt("fileName"))
            assertEquals(0L, cursor.longAt("attempts"))
        }

        // Writable at v10, i.e. the affinity is what the entity expects
        // rather than whatever the ALTER produced.
        db.execSQL("UPDATE `edit_queue` SET `attempts` = `attempts` + 1 WHERE `pageId` = 4242")
        db.query("SELECT `attempts` FROM `edit_queue` WHERE `pageId` = 4242").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.longAt("attempts"))
        }
        db.close()
    }

    @Test
    fun migrate7To8_addsTheRecentPagesIndexAndKeepsPages() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.insertPage(id = 11L, collectiveId = 3L, title = "Older", serverTimestamp = 100L)
            db.insertPage(id = 12L, collectiveId = 3L, title = "Newer", serverTimestamp = 200L)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        assertTrue(
            "MIGRATION_7_8 did not create index_pages_collectiveId_serverTimestamp",
            db.indexNames("pages").contains("index_pages_collectiveId_serverTimestamp"),
        )
        val recentTitles = db
            .query(
                "SELECT `title` FROM `pages` WHERE `collectiveId` = 3 " +
                    "ORDER BY `serverTimestamp` DESC",
            ).use { cursor ->
                buildList<String> {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
        assertEquals(listOf("Newer", "Older"), recentTitles)
        db.close()
    }
}

/**
 * Inserts a `pages` row naming only the columns that have existed since v1.
 * Every migration in the chain adds columns to `pages` rather than removing
 * them, so the same insert is valid at any version.
 */
private fun SupportSQLiteDatabase.insertPage(
    id: Long,
    collectiveId: Long,
    title: String,
    serverTimestamp: Long,
    bodyMd: String? = null,
) {
    val values = ContentValues().apply {
        put("id", id)
        put("collectiveId", collectiveId)
        put("parentId", 0L)
        put("title", title)
        putNull("emoji")
        put("tagsCsv", "")
        put("subpageOrderCsv", "")
        put("isFullWidth", 0)
        putNull("trashTimestamp")
        put("serverTimestamp", serverTimestamp)
        put("size", 0L)
        put("fileName", "$title.md")
        put("filePath", "Collective/$title.md")
        put("collectivePath", "Collective")
        put("linkedPageIdsCsv", "")
        put("lastUserDisplayName", "tester")
        if (bodyMd == null) {
            putNull("bodyMd")
        } else {
            put("bodyMd", bodyMd)
        }
        put("lastSyncedAt", 0L)
    }
    insert("pages", SQLiteDatabase.CONFLICT_ABORT, values)
}

/**
 * Inserts a `collectives` row naming only the columns that have existed since
 * v1, so the same insert is valid at any version — MIGRATION_8_9 only adds
 * columns. Pass [circleId] / [level] / [userShowMembers] to seed a row at v9
 * or later; they are omitted from the insert when null, which is what lets
 * this seed a v1 or v8 database too.
 */
private fun SupportSQLiteDatabase.insertCollective(
    id: Long,
    name: String,
    circleId: String? = null,
    level: Int? = null,
    userShowMembers: Boolean? = null,
) {
    val values = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("slug", name.lowercase())
        putNull("emoji")
        put("canEdit", 1)
        put("canShare", 1)
        put("isPageShare", 0)
        putNull("trashTimestamp")
        put("userFavoritePagesCsv", "")
        put("lastSyncedAt", 0L)
        // v9 columns (B-83). Named only when the caller asks for them: the
        // v1-seeded tests run against a table where they don't exist yet.
        if (circleId != null) put("circleId", circleId)
        if (level != null) put("level", level)
        if (userShowMembers != null) put("userShowMembers", if (userShowMembers) 1 else 0)
    }
    insert("collectives", SQLiteDatabase.CONFLICT_ABORT, values)
}

/** Every index SQLite reports against [table], Room's own included. */
private fun SupportSQLiteDatabase.indexNames(table: String): Set<String> {
    val sql = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'"
    return query(sql).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                add(cursor.getString(0))
            }
        }
    }
}

private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                add(cursor.stringAt("name"))
            }
        }
    }

/** Columns SQLite reports as part of [table]'s primary key. */
private fun SupportSQLiteDatabase.primaryKeyColumns(table: String): Set<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                if (cursor.intAt("pk") > 0) {
                    add(cursor.stringAt("name"))
                }
            }
        }
    }

private fun Cursor.longAt(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.intAt(column: String): Int = getInt(getColumnIndexOrThrow(column))

private fun Cursor.stringAt(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.isNullAt(column: String): Boolean = isNull(getColumnIndexOrThrow(column))
