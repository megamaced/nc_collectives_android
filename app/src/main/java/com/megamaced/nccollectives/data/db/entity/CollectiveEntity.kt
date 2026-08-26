package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collectives")
data class CollectiveEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val slug: String?,
    val emoji: String?,
    /**
     * Id of the Nextcloud Team backing the collective — the only handle the
     * Circles API accepts, and the reason this column exists (B-83).
     *
     * Nullable, and added by `MIGRATION_8_9` as a nullable column so rows
     * cached before v9 read back as "unknown" rather than as a plausible
     * id. They are refilled by the next `refresh()`, which is the app's
     * first network call on open.
     */
    val circleId: String?,
    val canEdit: Boolean,
    val canShare: Boolean,
    /** Raw Circles level of the signed-in user; 0 means the server didn't say. */
    val level: Int,
    /**
     * Server-side "show members" display preference. `MIGRATION_8_9` back-fills
     * pre-v9 rows with 1, matching `CollectiveDto`'s default — see the DTO for
     * why the absent case defaults to shown rather than hidden.
     */
    val userShowMembers: Boolean,
    val isPageShare: Boolean,
    val trashTimestamp: Long?,
    /** CSV of favorite-page ids; tiny lists, no need for a join table. */
    val userFavoritePagesCsv: String,
    val lastSyncedAt: Long,
)
