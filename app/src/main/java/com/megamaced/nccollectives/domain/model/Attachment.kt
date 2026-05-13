package com.megamaced.nccollectives.domain.model

/** One attachment belonging to a page. Mirrors the DB row in the domain layer. */
data class Attachment(
    val id: String,
    val pageId: Long,
    val fileName: String,
    val contentType: String?,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val status: Status,
    val remoteUrl: String?,
    val localUriString: String?,
) {
    enum class Status { REMOTE, PENDING, UPLOADING, FAILED }

    val isImage: Boolean get() = contentType?.startsWith("image/") == true
}
