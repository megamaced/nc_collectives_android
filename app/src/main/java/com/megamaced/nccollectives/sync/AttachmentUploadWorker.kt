package com.megamaced.nccollectives.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import timber.log.Timber

/**
 * Drains [AttachmentEntity] rows whose status is `PENDING`. For each row:
 *
 *  - resolve the page so we know the collective + file path
 *  - MKCOL the `.attachments.<pageId>` directory (no-op if it exists)
 *  - stream the local content:// URI through OkHttp without copying into RAM
 *  - flip the row to `REMOTE` on success, `FAILED` on hard error, leave
 *    `PENDING` and retry on transient network error
 */
@HiltWorker
class AttachmentUploadWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val pageDao: PageDao,
        private val attachmentDao: AttachmentDao,
        private val bodyService: PageBodyService,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val pending = attachmentDao.pendingUploads()
            if (pending.isEmpty()) return Result.success()

            var retry = false
            for (row in pending) {
                val page = pageDao.getById(row.pageId)
                if (page == null) {
                    Timber.w("Attachment %s references missing page %d", row.id, row.pageId)
                    attachmentDao.delete(row.id)
                    continue
                }
                val uriString = row.localUriString
                if (uriString.isNullOrEmpty()) {
                    Timber.w("Attachment %s has no local URI; marking failed", row.id)
                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    continue
                }

                attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_UPLOADING)

                val dir = AttachmentRepositoryImpl.attachmentsDirectoryFor(row.pageId)
                val ensure = bodyService.ensureCollection(
                    collectivePath = page.collectivePath,
                    filePath = page.filePath,
                    directoryName = dir,
                )
                when (ensure) {
                    is ApiResult.Success -> Unit
                    is ApiResult.NetworkError -> {
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_PENDING)
                        retry = true
                        continue
                    }
                    ApiResult.Unauthorised -> {
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_PENDING)
                        return Result.success() // SessionManager surfaces re-auth.
                    }
                    is ApiResult.HttpError, is ApiResult.Unexpected, ApiResult.Conflict -> {
                        Timber.w("MKCOL failed for %s: %s", row.id, ensure)
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                        continue
                    }
                }

                val uri = Uri.parse(uriString)
                val (body, contentType) = streamingBodyFor(uri, row.contentType)
                if (body == null) {
                    Timber.w("Couldn't open %s for upload", uri)
                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    continue
                }

                val put = bodyService.uploadFile(
                    collectivePath = page.collectivePath,
                    filePath = AttachmentRepositoryImpl.combinePath(page.filePath, dir),
                    fileName = row.fileName,
                    body = body,
                )
                when (put) {
                    is ApiResult.Success -> {
                        val size = sizeOf(uri)
                        attachmentDao.upsert(
                            row.copy(
                                contentType = contentType,
                                size = size,
                                etag = put.data,
                                lastModifiedMs = System.currentTimeMillis(),
                                status = AttachmentEntity.STATUS_REMOTE,
                                localUriString = null,
                                lastSyncedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    is ApiResult.NetworkError -> {
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_PENDING)
                        retry = true
                    }
                    ApiResult.Unauthorised -> {
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_PENDING)
                        return Result.success()
                    }
                    is ApiResult.HttpError -> {
                        Timber.w("Upload HTTP %d for %s: %s", put.code, row.id, put.message)
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    }
                    is ApiResult.Unexpected -> {
                        Timber.w(put.cause, "Upload unexpected error for %s", row.id)
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    }
                    ApiResult.Conflict -> {
                        Timber.w("Upload conflict for %s", row.id)
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    }
                }
            }
            return if (retry) Result.retry() else Result.success()
        }

        /**
         * Build a streaming [RequestBody] backed by [uri] so the file never
         * has to live entirely in RAM. Resolves the content type from the
         * resolver if [fallbackType] is null.
         */
        private suspend fun streamingBodyFor(
            uri: Uri,
            fallbackType: String?,
        ): Pair<RequestBody?, String?> =
            withContext(Dispatchers.IO) {
                val resolver = appContext.contentResolver
                val resolved = fallbackType ?: resolver.getType(uri)
                val mediaType = (resolved ?: "application/octet-stream").toMediaTypeOrNull()
                val length = sizeOf(uri)
                val body = object : RequestBody() {
                    override fun contentType() = mediaType

                    override fun contentLength(): Long = length

                    override fun writeTo(sink: BufferedSink) {
                        val input = resolver.openInputStream(uri)
                            ?: throw java.io.IOException("Unable to open $uri")
                        input.use { sink.writeAll(it.source()) }
                    }
                }
                body to resolved
            }

        private fun sizeOf(uri: Uri): Long {
            val resolver: ContentResolver = appContext.contentResolver
            return resolver.openAssetFileDescriptor(uri, "r").use { afd ->
                afd?.length ?: -1L
            }
        }
    }
