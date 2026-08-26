package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * One member of a Nextcloud Team (Circle), from
 * `GET /ocs/v2.php/apps/circles/circles/{circleId}/members`. Verified
 * against Circles 34.0.0 on Nextcloud 34.0.3.
 *
 * The omissions matter more than the fields, so they are spelled out:
 *
 *  - **There is deliberately no `notes` field, and adding one breaks the
 *    screen.** The server's `notes` is type-polymorphic *within a single
 *    response*: a JSON object on the member itself, and `[]` for the same
 *    key under `basedOn.owner.notes`. Both are PHP's `json_encode` of an
 *    empty associative array, which emits `{}` or `[]` depending on how
 *    the array was built. Declaring the field as either shape therefore
 *    throws on the members carrying the other one — and not on all of
 *    them, so it looks like a data problem rather than a schema one.
 *    `ignoreUnknownKeys` in `NetworkModule.provideJson()` skips the key
 *    whatever shape it arrives in, which makes *not declaring it* the only
 *    stable option.
 *  - `invitedBy` and `basedOn` are deep nested duplicates of whole member
 *    and circle records, and they are most of the ~2.2 KB each member
 *    costs on the wire. Nothing renders them.
 *  - `circleId`, `instance`, `local`, `status`, `joined`, `contactId`,
 *    `contactMeta` and `displayUpdate` are unrendered; the caller already
 *    knows which circle it asked about.
 */
@Serializable
data class CircleMemberDto(
    /**
     * Membership id — identifies this member *within this circle*. Required
     * rather than defaulted: a membership with no id is not a thing the UI
     * can key a list row on, so a response missing it is a contract break
     * worth surfacing as `ApiResult.Unexpected` rather than papering over.
     */
    val id: String,
    /**
     * Circles' cross-circle identity for the underlying principal. Stable
     * across circles, unlike [id], so this — not [id] — is the key for
     * "is this the same person in another team" comparisons.
     */
    val singleId: String = "",
    /** Login name: `user@host` for a federated member, bare uid locally. */
    val userId: String = "",
    /**
     * 0 single, 1 user, 2 group, 4 mail, 8 contact, 16 circle, 10000 app.
     * Defaulted to 0, which is also the real value for a single — an
     * absent `userType` is therefore indistinguishable from a single. Every
     * observed response carries the field, so that only bites on a
     * malformed one.
     */
    val userType: Int = 0,
    /** 1 member, 4 moderator, 8 admin, 9 owner. 0 means the server didn't say. */
    val level: Int = 0,
    /** Human-readable name. Can be blank for mail and contact memberships. */
    val displayName: String = "",
)
