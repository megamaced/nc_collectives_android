package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    indices = [
        Index("collectiveId"),
        Index(value = ["collectiveId", "parentId"]),
    ],
)
data class PageEntity(
    @PrimaryKey val id: Long,
    val collectiveId: Long,
    val parentId: Long,
    val title: String,
    val emoji: String?,
    val tagsCsv: String,
    val subpageOrderCsv: String,
    val isFullWidth: Boolean,
    val trashTimestamp: Long?,
    /** Server `timestamp` field — seconds since epoch. */
    val serverTimestamp: Long,
    val size: Long,
    val fileName: String,
    val filePath: String,
    val collectivePath: String,
    val linkedPageIdsCsv: String,
    val lastUserDisplayName: String,
    /**
     * Cached markdown body once first loaded over WebDAV. Null until viewed.
     * Edit-queue drafts are stored alongside in a future column (Batch 8).
     */
    val bodyMd: String?,
    /**
     * WebDAV ETag captured the last time the body was fetched. Used as the
     * `If-Match` precondition on save so we can detect server-side changes
     * since the body was loaded. Null until first fetched.
     */
    val bodyEtag: String?,
    val lastSyncedAt: Long,
)
