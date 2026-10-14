package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.DismissAllResult
import kotlinx.coroutines.flow.Flow

/**
 * Nextcloud notifications about collective pages: mentions, and changes in
 * collectives set to notify about them. Cached, so the Alerts tab renders
 * offline; in-app only, the app posts no system notifications.
 */
interface AlertRepository {
    /** The alerts to show, newest first. */
    fun observeAlerts(): Flow<List<Alert>>

    /** How many of [observeAlerts] the Alerts tab hasn't shown yet, for its badge. */
    fun observeUnseenCount(): Flow<Int>

    /** Whether the server provides notifications at all: null until it has said. */
    fun observeSupported(): Flow<Boolean?>

    /** Fetch the server's notifications and replace the cache with them. */
    suspend fun refresh(): ApiResult<Unit>

    /**
     * The Alerts tab has shown these. Named rather than "everything", so
     * one that arrived between the tab drawing its list and this landing
     * keeps its unseen mark. Written only while [generation] — the
     * `AccountGeneration` the tab was opened under — is current.
     */
    suspend fun markSeen(
        alertIds: Collection<Long>,
        generation: Long,
    )

    /** Dismiss one alert, on the server and here. */
    suspend fun dismiss(alertId: Long): ApiResult<Unit>

    /**
     * Dismiss every alert this app shows, one at a time — never the
     * server's dismiss-all, which would take other apps' notifications too.
     * Those the server refuses stay.
     */
    suspend fun dismissAll(): DismissAllResult
}
