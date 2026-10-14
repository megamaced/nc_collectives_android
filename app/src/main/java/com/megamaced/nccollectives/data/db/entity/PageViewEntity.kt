package com.megamaced.nccollectives.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A page this device has shown the user: local-only history behind Home's
 * "Jump back in" and "Updated since you read it". Never sent anywhere.
 *
 * No foreign key to `pages`, as with the edit queue: the page repository
 * moves a row when the server reissues a page id and drops it when the page
 * leaves the cache, and the account wipe clears the table.
 */
@Entity(
    tableName = "page_views",
    indices = [Index("lastViewedAt")],
)
data class PageViewEntity(
    @PrimaryKey val pageId: Long,
    /** When the page was last on screen, epoch millis on this device. */
    val lastViewedAt: Long,
    /**
     * Server timestamp, in seconds, of the newest version of the page the
     * user has been shown. 0 when no shown version had a known timestamp,
     * which never counts as "updated since you read it".
     */
    val seenTimestamp: Long,
)
