package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.ServerStatusDto
import retrofit2.http.GET

/**
 * Nextcloud's `status.php`. Unauthenticated and un-wrapped (no OCS
 * envelope), but still routed through the shared `OkHttpClient` so
 * `HostInterceptor` rewrites the placeholder host to the user's own server
 * — this must never reach a third party (see the v2.3.9 F-Droid learning:
 * the app talks only to the user's Nextcloud).
 */
interface ServerStatusService {
    @GET("status.php")
    suspend fun getStatus(): ServerStatusDto
}
