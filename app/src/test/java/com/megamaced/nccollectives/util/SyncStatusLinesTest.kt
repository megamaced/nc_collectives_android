package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.data.prefs.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the Settings sync-status wording. These strings are the whole point
 * of the status line — they're what lets someone tell "nothing has changed"
 * apart from "syncing has been broken for two days".
 */
class SyncStatusLinesTest {
    private val now = TimeUnit.DAYS.toMillis(20_000)

    @Test
    fun neverSynced_saysSo() {
        val lines = syncStatusLines(SyncStatus(), now)
        assertEquals("Not synced yet", lines.summary)
        assertNull(lines.error)
    }

    @Test
    fun secondsAgo_readsAsJustNow() {
        val lines = syncStatusLines(SyncStatus(lastSuccessAt = now - 30_000L), now)
        assertEquals("Last synced just now", lines.summary)
    }

    @Test
    fun singularAndPluralUnits() {
        assertEquals(
            "Last synced 1 minute ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.MINUTES.toMillis(1)), now).summary,
        )
        assertEquals(
            "Last synced 5 minutes ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.MINUTES.toMillis(5)), now).summary,
        )
        assertEquals(
            "Last synced 1 hour ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.HOURS.toMillis(1)), now).summary,
        )
        assertEquals(
            "Last synced 6 hours ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.HOURS.toMillis(6)), now).summary,
        )
        assertEquals(
            "Last synced 1 day ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.DAYS.toMillis(1)), now).summary,
        )
        assertEquals(
            "Last synced 3 days ago",
            syncStatusLines(SyncStatus(lastSuccessAt = now - TimeUnit.DAYS.toMillis(3)), now).summary,
        )
    }

    @Test
    fun failureDoesNotHideTheLastSuccess() {
        // Both halves matter: how old the data is, and whether it's still
        // getting older on purpose.
        val lines = syncStatusLines(
            SyncStatus(
                lastSuccessAt = now - TimeUnit.DAYS.toMillis(2),
                lastFailureAt = now - TimeUnit.MINUTES.toMillis(10),
                lastFailureMessage = "Couldn't reach the server. Check your connection.",
            ),
            now,
        )
        assertEquals("Last synced 2 days ago", lines.summary)
        assertEquals(
            "Last attempt 10 minutes ago failed: Couldn't reach the server. Check your connection.",
            lines.error,
        )
    }

    @Test
    fun failureWithNoPriorSuccess_reportsBoth() {
        val lines = syncStatusLines(
            SyncStatus(
                lastFailureAt = now - TimeUnit.MINUTES.toMillis(2),
                lastFailureMessage = "Server returned 500",
            ),
            now,
        )
        assertEquals("Not synced yet", lines.summary)
        assertEquals("Last attempt 2 minutes ago failed: Server returned 500", lines.error)
    }

    @Test
    fun messageWithoutTimestamp_isIgnored() {
        // Defensive: a message with no `lastFailureAt` would otherwise render
        // as "Last attempt <the epoch> failed".
        val lines = syncStatusLines(
            SyncStatus(lastSuccessAt = now - 1_000L, lastFailureMessage = "stale"),
            now,
        )
        assertNull(lines.error)
    }

    @Test
    fun futureTimestamp_readsAsJustNow() {
        // Device clock adjustments must not produce "Last synced -3 minutes ago".
        val lines = syncStatusLines(SyncStatus(lastSuccessAt = now + TimeUnit.HOURS.toMillis(3)), now)
        assertEquals("Last synced just now", lines.summary)
    }
}
