package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page

/**
 * Launches embedded Nextcloud Text sessions for Collectives pages via the
 * Files `directEditing` OCS API. Backed by [DirectEditingService] —
 * Batch 27 wires the API; UI lands in Batch 28+.
 *
 * Server compatibility:
 * - Nextcloud Server ≥ 18 to expose the endpoint.
 * - Text app installed and registering `text/markdown` as a supported
 *   mimetype (true for any current Text 2.x+).
 *
 * Older / mis-configured servers surface as [isAvailable] returning
 * `false` (404 on capability) or no editor handling `text/markdown` —
 * callers must fall through to the native editor in that case.
 */
interface DirectEditingRepository {
    /**
     * One-shot capability check. Result is cached for the lifetime of
     * the singleton — re-launch the app to refresh. Single network
     * request per session, not per editor open.
     *
     * Returns `true` only if an editor handles `text/markdown` (or
     * lists it as an optional mimetype).
     */
    suspend fun isAvailable(): Boolean

    /**
     * Request a fresh one-shot signed URL for [page]. The token in the
     * URL is consumed on first load — config-change reloads, process
     * death restores, and back-then-forward navigation must each call
     * this again.
     */
    suspend fun openSession(page: Page): ApiResult<String>
}
