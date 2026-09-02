package com.megamaced.nccollectives.domain.repository

import android.net.Uri
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.model.OpenableAttachment
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun observeForPage(pageId: Long): Flow<List<Attachment>>

    /** Refresh the attachment list for [pageId] by PROPFIND-ing the server. */
    suspend fun refresh(pageId: Long): ApiResult<Unit>

    /**
     * Enqueue an attachment upload from [sourceUri]. Bytes are copied into
     * the app's internal cache before this returns (B-29) so the WorkManager
     * upload survives the source URI's permission grant being revoked, the
     * sender uninstalling, or the photo-picker cache being evicted. The
     * actual byte transfer happens in `AttachmentUploadWorker`.
     *
     * Returns the *resolved* filename — `enqueueUpload` runs the suggested
     * name through `sanitiseFileName` + a collision-resolver, so the caller
     * should use the returned string (not [suggestedFileName]) when emitting
     * markdown references that will resolve against the server-side file.
     * Returns null if the source URI couldn't be read.
     */
    suspend fun enqueueUpload(
        pageId: Long,
        sourceUri: Uri,
        suggestedFileName: String,
        contentType: String?,
    ): String?

    suspend fun delete(
        pageId: Long,
        fileName: String,
    ): ApiResult<Unit>

    /**
     * Put a failed upload back in the queue (issue #23).
     *
     * `FAILED` used to be a dead end: the worker only ever selects `PENDING`
     * and `UPLOADING`, and nothing anywhere moved a row back, so an upload
     * that failed once was unrecoverable — while the grid drew a progress
     * spinner over it, because it drew one over every non-`REMOTE` status.
     *
     * Fails when the staged bytes are gone, which is what makes this
     * honest rather than a button that silently fails again: the cache
     * directory is evictable, and several of the worker's arms delete the
     * staging copy deliberately.
     */
    suspend fun retryUpload(
        pageId: Long,
        fileName: String,
    ): ApiResult<Unit>

    /** Build the WebDAV URL Coil should hit for this attachment. */
    suspend fun urlFor(
        pageId: Long,
        fileName: String,
    ): String?

    /**
     * Download the attachment at [relativePath] (relative to [pageId]'s own
     * directory, e.g. `.attachments.12/report.pdf`) into the app cache and
     * return it as something `ACTION_VIEW` can open.
     *
     * Covers the non-image half of the attachment story: PDFs, office
     * documents and the like can't be rendered inline, so the only useful
     * thing to do with a tap is stage the bytes and let the user's own
     * viewer app take over.
     */
    suspend fun downloadForViewing(
        pageId: Long,
        relativePath: String,
    ): ApiResult<OpenableAttachment>

    /**
     * Absolute URL of the `.attachments.<pageId>` directory itself, with a
     * trailing slash. Used by the markdown renderer to resolve relative
     * image refs like `![](photo.jpg)`.
     */
    suspend fun attachmentsBaseUrl(pageId: Long): String?
}
