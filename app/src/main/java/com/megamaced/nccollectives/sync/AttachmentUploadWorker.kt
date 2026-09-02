package com.megamaced.nccollectives.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
        private val accountGeneration: AccountGeneration,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            // Issue #20: the account this run's writes belong to. The status
            // updates below are `UPDATE`s and match nothing once the tables
            // have been cleared, but the success arm's upsert is an insert —
            // it would hand the incoming account an attachment row, and a
            // readable file, belonging to the outgoing one.
            val generation = accountGeneration.current()
            val pending = attachmentDao.pendingUploads()
            if (pending.isEmpty()) return Result.success()

            var retry = false
            for (row in pending) {
                // B-63: one row must never take the drain down with it.
                // `pendingUploads()` selects `UPLOADING` as well as
                // `PENDING`, so a row that threw out of `doWork` was left
                // mid-upload *and* still selected — every later run picked it
                // first, threw again, and the whole queue behind it never
                // moved. Anything unexpected now fails that one row (with its
                // staged bytes collected) and the loop carries on.
                try {
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
                        is ApiResult.Success -> {
                            Unit
                        }

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
                        gcStaged(row.id)
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
                            if (!accountGeneration.isCurrent(generation)) {
                                Timber.i("Account changed mid-upload; not recording %s", row.id)
                                gcStaged(row.id)
                                return Result.success()
                            }
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
                            // B-29: bytes are safely on the server now; drop the
                            // staging copy.
                            gcStaged(row.id)
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
                            gcStaged(row.id)
                        }

                        is ApiResult.Unexpected -> {
                            Timber.w(put.cause, "Upload unexpected error for %s", row.id)
                            attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                            gcStaged(row.id)
                        }

                        ApiResult.Conflict -> {
                            Timber.w("Upload conflict for %s", row.id)
                            attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                            gcStaged(row.id)
                        }
                    }
                } catch (e: CancellationException) {
                    // B-45 / R-40: cancellation is not this row's fault and
                    // must tear the worker down, not mark anything failed.
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Upload of %s threw; marking it failed and carrying on", row.id)
                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                    gcStaged(row.id)
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
                // B-63: probe the URI before handing OkHttp a body that can
                // only fail once the request is on the wire. A staged cache
                // file evicted under storage pressure surfaces as
                // FileNotFoundException inside [RequestBody.writeTo], which
                // OkHttp reports as an IOException — indistinguishable from
                // "the network dropped", so the row went back to PENDING and
                // retried forever against bytes that no longer exist.
                // Returning a null body routes it to the `FAILED` branch the
                // caller already has.
                val openable = runCatching { resolver.openInputStream(uri)?.use { true } }.getOrNull() == true
                if (!openable) return@withContext null to resolved
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

        /**
         * Byte length of [uri]'s content, or -1 when it can't be determined
         * — which is a legitimate answer, not an error: OkHttp falls back to
         * chunked transfer encoding for a body of unknown length.
         *
         * B-63: `openAssetFileDescriptor` throws FileNotFoundException for a
         * staged cache file that has since been evicted, and both call sites
         * sit outside any `ApiResult` boundary. Unguarded, the throw came out
         * of `doWork` itself: WorkManager recorded a `failure`, and the row
         * stayed `UPLOADING` — a status `pendingUploads()` still selects, so
         * the queue wedged on it permanently.
         */
        private fun sizeOf(uri: Uri): Long =
            runCatching {
                val resolver: ContentResolver = appContext.contentResolver
                resolver.openAssetFileDescriptor(uri, "r").use { afd -> afd?.length ?: -1L }
            }.getOrDefault(-1L)

        private fun gcStaged(attachmentId: String) {
            val staged = AttachmentRepositoryImpl.stagedFileFor(appContext, attachmentId)
            if (staged.exists() && !staged.delete()) {
                Timber.w("Couldn't delete staged upload %s", staged.absolutePath)
            }
        }
    }
