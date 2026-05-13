package com.megamaced.nccollectives.domain.repository

import android.net.Uri
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Attachment
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun observeForPage(pageId: Long): Flow<List<Attachment>>

    /** Refresh the attachment list for [pageId] by PROPFIND-ing the server. */
    suspend fun refresh(pageId: Long): ApiResult<Unit>

    /**
     * Enqueue an attachment upload from [sourceUri]. The actual byte transfer
     * happens in `AttachmentUploadWorker`. Returns the resolved attachment id
     * the UI can reference (e.g. to insert into markdown after upload).
     */
    suspend fun enqueueUpload(
        pageId: Long,
        sourceUri: Uri,
        suggestedFileName: String,
        contentType: String?,
    ): String

    suspend fun delete(
        pageId: Long,
        fileName: String,
    ): ApiResult<Unit>

    /** Build the WebDAV URL Coil should hit for this attachment. */
    suspend fun urlFor(
        pageId: Long,
        fileName: String,
    ): String?

    /**
     * Absolute URL of the `.attachments.<pageId>` directory itself, with a
     * trailing slash. Used by the markdown renderer to resolve relative
     * image refs like `![](photo.jpg)`.
     */
    suspend fun attachmentsBaseUrl(pageId: Long): String?
}
