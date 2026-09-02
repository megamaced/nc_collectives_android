package com.megamaced.nccollectives.sync

import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #18, the in-flight half: `EditFlushWorker` settles from a snapshot
 * taken before the PUT, and the user can save again while the row is
 * `IN_FLIGHT`. Deleting the row on the strength of the snapshot discards an
 * edit the server has never seen.
 */
class SettledQueueRowTest {
    private fun row(
        body: String,
        baseEtag: String? = "etag-1",
        status: String = "IN_FLIGHT",
    ) = EditQueueEntity(
        pageId = 7L,
        baseEtag = baseEtag,
        newBodyMd = body,
        queuedAt = 1_000L,
        status = status,
        forceWrite = false,
    )

    @Test
    fun `an unchanged row is deleted`() {
        assertNull(
            settledQueueRow(current = row("base + A"), flushedBody = "base + A", newEtag = "etag-2"),
        )
    }

    @Test
    fun `an already-absent row is nothing to settle`() {
        assertNull(settledQueueRow(current = null, flushedBody = "base + A", newEtag = "etag-2"))
    }

    @Test
    fun `an edit queued while the put was in flight survives`() {
        val survivor = settledQueueRow(
            current = row("base + A + B"),
            flushedBody = "base + A",
            newEtag = "etag-2",
        )
        assertNotNull(survivor)
        assertEquals("base + A + B", survivor?.newBodyMd)
    }

    @Test
    fun `a survivor is re-based on the version just written`() {
        // Edit B was authored on top of A, and A is now what the server
        // holds. Leaving `baseEtag` at etag-1 would make the next flush
        // report a conflict against the user's own successful write.
        val survivor = settledQueueRow(
            current = row("base + A + B", baseEtag = "etag-1"),
            flushedBody = "base + A",
            newEtag = "etag-2",
        )
        assertEquals("etag-2", survivor?.baseEtag)
    }

    @Test
    fun `a survivor is re-armed for the next run`() {
        val survivor = settledQueueRow(
            current = row("base + A + B", status = "IN_FLIGHT"),
            flushedBody = "base + A",
            newEtag = "etag-2",
        )
        assertEquals("PENDING", survivor?.status)
    }
}
