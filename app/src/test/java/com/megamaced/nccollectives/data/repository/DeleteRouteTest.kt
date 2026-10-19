package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #35: deleting a non-`REMOTE` attachment used to drop the Room row and
 * report success, which honoured nothing — removing the row neither cancels a
 * PUT already on the wire nor undoes one that has landed, so the bytes stayed
 * on the server and the next listing inserted them again as `REMOTE`.
 */
class DeleteRouteTest {
    private fun row(status: String) =
        AttachmentEntity(
            id = AttachmentEntity.key(7L, "shot.png"),
            pageId = 7L,
            fileName = "shot.png",
            contentType = "image/png",
            size = 1L,
            lastModifiedMs = 0L,
            etag = null,
            status = status,
            localUriString = null,
            lastSyncedAt = 0L,
        )

    @Test
    fun `an uploading row is tombstoned`() {
        // Its PUT may be on the wire right now.
        assertEquals(DeleteRoute.Tombstone, deleteRoute(row(AttachmentEntity.STATUS_UPLOADING)))
    }

    @Test
    fun `a row read as pending is tombstoned`() {
        // Only asked when it could not be dropped: a worker claimed it
        // between this read and the delete (issue #56).
        assertEquals(DeleteRoute.Tombstone, deleteRoute(row(AttachmentEntity.STATUS_PENDING)))
    }

    @Test
    fun `a row read as failed is tombstoned`() {
        // As above: a Retry put it back and a worker claimed it since.
        assertEquals(DeleteRoute.Tombstone, deleteRoute(row(AttachmentEntity.STATUS_FAILED)))
    }

    @Test
    fun `an already-tombstoned row stays a tombstone`() {
        assertEquals(DeleteRoute.Tombstone, deleteRoute(row(AttachmentEntity.STATUS_DELETING)))
    }

    @Test
    fun `a remote row is deleted on the server by its id`() {
        assertEquals(DeleteRoute.ServerById, deleteRoute(row(AttachmentEntity.STATUS_REMOTE)))
    }

    @Test
    fun `a row this device has never seen is the server's`() {
        assertEquals(DeleteRoute.ServerById, deleteRoute(null))
    }
}
