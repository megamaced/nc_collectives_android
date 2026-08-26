package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.CircleMemberDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The slice of the Nextcloud Teams (Circles) OCS API this app needs.
 *
 * Collectives has no members endpoint: a collective's membership *is* a
 * Team, so member reads go to Circles keyed by the collective's
 * `circleId`. Paths are relative to the placeholder base URL, like
 * [CollectivesApiService] — `HostInterceptor` rewrites scheme/host/port at
 * request time and `AuthInterceptor` adds Basic-auth plus the
 * `OCS-APIRequest: true` header that OCS requires (without it Nextcloud
 * answers 412 on CSRF grounds).
 *
 * Note the path shape: Circles' OCS routes are **unversioned**
 * (`/apps/circles/circles/...`), unlike the Collectives ones. Adding a
 * `/v1/` segment produces a 404, verified against Circles 34.0.0.
 */
interface CirclesApiService {
    /**
     * Members of one Team. `data` is a bare JSON array, so the existing
     * [Envelope] generic wraps it directly rather than needing a
     * single-key `…EnvelopeData` holder like the Collectives endpoints.
     *
     * [limit] is mandatory and small on purpose. Each member costs ~2.2 KB
     * even in this trimmed form because `invitedBy` and `basedOn` are deep
     * nested duplicates, so an unbounded read of a 200-member Team is
     * ~440 KB. `fullDetails=true` is deliberately never sent: measured at
     * 6548 bytes against 2194 for a single member, and all it adds is a
     * nested copy of the circle the caller already has.
     *
     * **403 is normal here, and must never be retried.** Every Circles
     * controller method carries `#[BruteForceProtection]` and calls
     * `throttle()` when a permission check fails, so a client that retries
     * gets the *user's own IP* throttled. It is also the expected answer
     * for a non-member and is indistinguishable from "no such circle" —
     * the server declines to say which. `CollectiveRepository.listMembers`
     * is where that policy is enforced; nothing in this app retries an
     * `ApiResult.HttpError`.
     */
    @GET("ocs/v2.php/apps/circles/circles/{circleId}/members")
    suspend fun listMembers(
        @Path("circleId") circleId: String,
        @Query("limit") limit: Int,
    ): Envelope<List<CircleMemberDto>>
}
