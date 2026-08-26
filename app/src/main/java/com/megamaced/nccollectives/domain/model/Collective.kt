package com.megamaced.nccollectives.domain.model

data class Collective(
    val id: Long,
    val name: String,
    val slug: String?,
    val emoji: String?,
    /**
     * Id of the Nextcloud Team backing this collective, or null when the
     * server didn't send one (B-83).
     *
     * Nullable rather than an empty-string sentinel: it is the sole handle
     * for every membership call, so "we cannot address this collective's
     * members" is a state callers have to handle, and a nullable type makes
     * the compiler say so. An empty string would turn that into a `== ""`
     * check nobody remembers to write, and a blank path segment produces a
     * request to a different route entirely.
     */
    val circleId: String?,
    val canEdit: Boolean,
    val canShare: Boolean,
    /**
     * The signed-in user's raw Circles level: 1 member, 4 moderator,
     * 8 admin, 9 owner, and 0 for "the server didn't say" (no real level is
     * 0). Kept as the wire `Int` because that is exactly what the cached
     * column holds; use [userLevel] to compare or label it.
     */
    val level: Int,
    /**
     * The web app's per-user "show members" preference. A display hint for
     * whether to surface the members entry point — *not* an authorisation
     * check. Only the server knows whether this user may read the member
     * list, and it answers 403 when they may not.
     */
    val userShowMembers: Boolean,
    val isPageShare: Boolean,
    val trashed: Boolean,
    val favoritePageIds: Set<Long>,
) {
    /**
     * [level] as the same enum member levels use, so role checks and role
     * labels have one implementation instead of one per screen.
     */
    val userLevel: CollectiveMemberLevel
        get() = CollectiveMemberLevel.fromRaw(level)
}
