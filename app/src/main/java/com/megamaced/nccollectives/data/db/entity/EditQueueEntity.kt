package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pending markdown-body save. Rows are enqueued when a save can't make it
 * through to the server immediately (offline, or earlier failure) and drained
 * by `EditFlushWorker` once the network returns.
 *
 * `baseEtag` is the ETag the user saw when they started editing — sent as
 * `If-Match` on the eventual PUT so the worker can detect server-side changes
 * that happened while the edit was queued.
 */
@Entity(
    tableName = "edit_queue",
    indices = [Index("pageId", unique = true)],
)
data class EditQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val baseEtag: String?,
    val newBodyMd: String,
    val queuedAt: Long,
    /** One of "PENDING", "IN_FLIGHT", "CONFLICTED". */
    val status: String,
)
