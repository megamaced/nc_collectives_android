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
    val level: Int = 0,
    val editPermissionLevel: Int = 0,
    val sharePermissionLevel: Int = 0,
    val canEdit: Boolean = false,
    val canShare: Boolean = false,
    val shareToken: String? = null,
    val isPageShare: Boolean = false,
    val trashTimestamp: Long? = null,
    val userFavoritePages: List<Long> = emptyList(),
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
