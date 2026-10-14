package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached Nextcloud notification the Alerts tab shows, so the tab renders
 * offline. [id] is the server's notification id, which is what dismissing
 * one deletes.
 *
 * [verified] is whether the server tied the notification to a collective
 * itself: Collectives' own notifications always are, and so are Text
 * mentions its listener recognised. A Text mention without that is kept
 * but only shown while [pageId] is a cached page, so it appears once a sync
 * brings the page in.
 *
 * [seen] is this device's, since Nextcloud has no read flag; a refresh
 * carries it over for ids it already had.
 */
@Entity(tableName = "alerts", indices = [Index("timestamp")])
data class AlertEntity(
    @PrimaryKey val id: Long,
    /** An `AlertKind` name. */
    val kind: String,
    val pageId: Long,
    val collectiveId: Long?,
    val actorName: String?,
    val pageTitle: String?,
    val collectiveName: String?,
    val subject: String,
    /** Seconds since the epoch. */
    val timestamp: Long,
    val verified: Boolean,
    val seen: Boolean,
)
