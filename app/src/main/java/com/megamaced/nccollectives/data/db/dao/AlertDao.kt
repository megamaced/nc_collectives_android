package com.megamaced.nccollectives.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.megamaced.nccollectives.data.db.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

/**
 * The cached alerts. "Visible" is the one rule everything here shares — the
 * list, the badge and dismiss-all: verified alerts, plus unverified Text
 * mentions whose page is a live cached page in a live collective, as Home
 * counts them (see [AlertEntity.verified]).
 */
@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE $VISIBLE ORDER BY timestamp DESC, id DESC")
    fun observeVisible(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE seen = 0 AND $VISIBLE")
    fun observeUnseenCount(): Flow<Int>

    @Query("SELECT id FROM alerts WHERE $VISIBLE")
    suspend fun visibleIds(): List<Long>

    /** Every cached id that has been shown, visible or not, to carry over a refresh. */
    @Query("SELECT id FROM alerts WHERE seen = 1")
    suspend fun seenIds(): List<Long>

    @Upsert
    suspend fun upsertAll(alerts: List<AlertEntity>)

    /** Drop every alert the server no longer lists. */
    @Query("DELETE FROM alerts WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)

    @Query("UPDATE alerts SET seen = 1 WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<Long>)

    @Query("DELETE FROM alerts WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM alerts")
    suspend fun clear()
}

private const val VISIBLE =
    "(verified = 1 OR EXISTS (SELECT 1 FROM pages p WHERE p.id = alerts.pageId AND p.trashTimestamp IS NULL " +
        "AND EXISTS (SELECT 1 FROM collectives c WHERE c.id = p.collectiveId AND c.trashTimestamp IS NULL)))"
