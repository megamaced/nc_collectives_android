package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shape returned for each tag in `GET /collectives/{id}/tags`. Collectives
 * uses Nextcloud system tags so `id` and `name` are what we need; color
 * (when present) is a hex string but Batch 11 ignores it.
 */
@Serializable
data class TagDto(
    val id: Long,
    val name: String = "",
    val color: String? = null,
)

@Serializable
data class TagsEnvelopeData(
    val tags: List<TagDto> = emptyList(),
)

/** Single-tag envelope returned by `POST /tags` (create) and `PUT /tags/{id}`. */
@Serializable
data class TagEnvelopeData(
    val tag: TagDto,
)
