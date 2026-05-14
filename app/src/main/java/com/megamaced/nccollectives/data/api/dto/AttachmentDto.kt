package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Shape returned by `GET /collectives/{cId}/pages/{id}/attachments`.
 *
 * Per `collectives-mcp/ENDPOINTS.md` gotcha #9 the server's response has
 * no `pageId` field — callers track it from their own context. Fields
 * other than `id` / `name` / `mimetype` are best-effort and defaulted.
 */
@Serializable
data class AttachmentDto(
    val id: Long,
    val name: String,
    val filesize: Long = 0,
    val mimetype: String? = null,
    /** Last-modified, seconds since epoch. */
    val timestamp: Long = 0,
    val path: String? = null,
    val internalPath: String? = null,
    val hasPreview: Boolean = false,
)

@Serializable
data class AttachmentsEnvelopeData(
    val attachments: List<AttachmentDto> = emptyList(),
)
