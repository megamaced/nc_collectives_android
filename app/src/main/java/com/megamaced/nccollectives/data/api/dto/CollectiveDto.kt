package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shape returned for each collective in `GET /apps/collectives/api/v1.0/collectives`.
 * Unknown fields are ignored at deserialisation time so this DTO can lag behind
 * Collectives' minor schema additions.
 */
@Serializable
data class CollectiveDto(
    val id: Long,
    val name: String,
    val slug: String? = null,
    val emoji: String? = null,
    /**
     * Id of the Nextcloud Team (Circle) backing this collective.
     *
     * B-83: the payload has always carried this and the mapper dropped it,
     * which left the app unable to address the Circles API at all — and
     * Collectives has no members endpoint of its own, so every membership
     * read has to be keyed by this id rather than by [id].
     *
     * Nullable because it is not load-bearing for the collective list
     * itself: an older Collectives that omits it must still list, and
     * degrade to "membership is unreachable here" instead of failing the
     * whole parse.
     */
    val circleId: String? = null,
    /**
     * The signed-in user's Circles level in [circleId] — 1 member,
     * 4 moderator, 8 admin, 9 owner. `0` is not a level the server ever
     * sends, so it doubles as "this server didn't say".
     */
    val level: Int = 0,
    val editPermissionLevel: Int = 0,
    val sharePermissionLevel: Int = 0,
    val canEdit: Boolean = false,
    val canShare: Boolean = false,
    val shareToken: String? = null,
    val isPageShare: Boolean = false,
    val trashTimestamp: Long? = null,
    val userFavoritePages: List<Long> = emptyList(),
    /**
     * The web app's per-user "show members" preference for this collective.
     *
     * Defaults to `true` on purpose: absent means the server keeps no such
     * setting, not that the user opted out, and defaulting the other way
     * would hide the members entry point on precisely the servers we can't
     * interrogate. It is a display hint and never a permission check — the
     * server's 403 is the permission check, and it is the only one that
     * knows the answer.
     */
    val userShowMembers: Boolean = true,
)

@Serializable
data class CollectivesEnvelopeData(
    val collectives: List<CollectiveDto> = emptyList(),
)

/**
 * Single-collective wrapper returned by the create / update / delete
 * endpoints under `{ ocs: { data: { collective: { ... } } } }`.
 */
@Serializable
data class CollectiveEnvelopeData(
    val collective: CollectiveDto,
)
