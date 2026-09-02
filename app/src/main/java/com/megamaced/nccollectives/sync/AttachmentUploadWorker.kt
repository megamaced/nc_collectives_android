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
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.ui.screen.isRetryableFailure
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
 *  - flip the row to `REMOTE` on success, `FAILED` on a failure retrying
 *    can't fix, or leave it `PENDING` for another run
 *
 * Issue #23: which of the last two a failure gets is [uploadFailureAction]'s
 * decision, and it used to be neither — every HTTP status, every `Conflict`
 * and every unexpected exception went straight to `FAILED`, so a 503 behind
 * a reverse proxy or a 429 from rate limiting lost the upload as surely as a
 * 403 did. A `FAILED` row also kept its staged bytes only by accident, and
 * `pendingUploads()` never selects one again, so the file was gone with the
 * grid still drawing a spinner over it.
 *
 * A `FAILED` row now keeps its staged bytes on purpose, so
 * `AttachmentRepository.retryUpload` has something to send. The two arms that
 * still drop them are the two where there is nothing to keep: bytes that
 * can't be opened, and a row with no local URI at all.
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
        private val attachmentRepository: AttachmentRepository,
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
                    // Issue #35: a tombstone is not an upload. The user
                    // cancelled this attachment while its bytes may already
                    // have reached the server, so the work is to remove the
                    // remote object — the row is only still here so that can
                    // survive a process restart.
                    if (row.status == AttachmentEntity.STATUS_DELETING) {
                        val deleted = attachmentRepository.resolveDeletion(row.pageId, row.fileName)
                        if (deleted !is ApiResult.Success) {
                            Timber.w("Couldn't remove cancelled upload %s yet: %s", row.id, deleted)
                            retry = true
                        }
                        continue
                    }
                    val page = pageDao.getById(row.pageId)
                    if (page == null) {
                        Timber.w("Attachment %s references missing page %d", row.id, row.pageId)
                        attachmentDao.delete(row.id)
                        continue
                    }
                    val uriString = row.localUriString
                    if (uriString.isNullOrEmpty()) {
                        // Nothing to send and nothing a retry could fix.
                        Timber.w("Attachment %s has no local URI; marking failed", row.id)
                        attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                        gcStaged(row.id)
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
                            when (uploadFailureAction(ensure, runAttemptCount)) {
                                UploadFailureAction.RetryLater -> {
                                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_PENDING)
                                    retry = true
                                }

                                UploadFailureAction.Terminal -> {
                                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
                                }
                            }
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
                            // Issue #23: `row` is a snapshot from before the
                            // PUT, and the upsert below is an insert. A row
                            // that has gone or been tombstoned since must not
                            // be recreated as REMOTE by its own upload
                            // finishing.
                            //
                            // Issue #35: a delete leaves a DELETING row
                            // rather than no row, so that arm hands the
                            // just-uploaded object to `resolveDeletion` —
                            // which is the difference between the UI's
                            // "deleted" being true and the file reappearing
                            // on the next listing. A missing row means the
                            // page itself went (a collective cascade), and
                            // there is nothing addressable left to delete.
                            val current = attachmentDao.getById(row.id)
                            if (current == null) {
                                Timber.i("Attachment %s vanished mid-upload; not recording it", row.id)
                                gcStaged(row.id)
                                continue
                            }
                            if (current.status == AttachmentEntity.STATUS_DELETING) {
                                Timber.i("Attachment %s was cancelled mid-upload; removing what landed", row.id)
                                if (attachmentRepository.resolveDeletion(row.pageId, row.fileName) !is ApiResult.Success) {
                                    retry = true
                                }
                                continue
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
                            if (settleUploadFailure(row.id, put)) retry = true
                        }

                        is ApiResult.Unexpected -> {
                            Timber.w(put.cause, "Upload unexpected error for %s", row.id)
                            if (settleUploadFailure(row.id, put)) retry = true
                        }

                        ApiResult.Conflict -> {
                            // Issue #24: `If-None-Match: *` refused, so the
                            // name is taken on the server. Re-queue under the
                            // next free one rather than overwrite a file this
                            // device didn't put there.
                            Timber.w("Upload name for %s is taken on the server", row.id)
                            if (attachmentRepository.renameForRemoteCollision(row.pageId, row.fileName) != null) {
                                retry = true
                            } else if (settleUploadFailure(row.id, put)) {
                                retry = true
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // B-45 / R-40: cancellation is not this row's fault and
                    // must tear the worker down, not mark anything failed.
                    throw e
                } catch (e: Exception) {
                    // B-63: one row must never take the drain down with it,
                    // and `FAILED` is what stops `pendingUploads()` handing
                    // it back. Issue #23: the staged bytes stay, so the user
                    // can retry from the grid rather than re-pick a file
                    // they may no longer have.
                    Timber.w(e, "Upload of %s threw; marking it failed and carrying on", row.id)
                    attachmentDao.setStatus(row.id, AttachmentEntity.STATUS_FAILED)
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

        /**
         * Mark one row's failure, and say whether the drain should ask for
         * another run. Staged bytes are kept either way: a `PENDING` row
         * needs them for the retry, and a `FAILED` one needs them for the
         * user's (issue #23).
         */
        private suspend fun settleUploadFailure(
            attachmentId: String,
            result: ApiResult<*>,
        ): Boolean =
            when (uploadFailureAction(result, runAttemptCount)) {
                UploadFailureAction.RetryLater -> {
                    attachmentDao.setStatus(attachmentId, AttachmentEntity.STATUS_PENDING)
                    true
                }

                UploadFailureAction.Terminal -> {
                    attachmentDao.setStatus(attachmentId, AttachmentEntity.STATUS_FAILED)
                    false
                }
            }

        private fun gcStaged(attachmentId: String) {
            val staged = AttachmentRepositoryImpl.stagedFileFor(appContext, attachmentId)
            if (staged.exists() && !staged.delete()) {
                Timber.w("Couldn't delete staged upload %s", staged.absolutePath)
            }
        }
    }

/** What a failed upload attempt means for the row. */
internal enum class UploadFailureAction {
    /** Left `PENDING`; ask WorkManager for another run. */
    RetryLater,

    /** Marked `FAILED`. Its staged bytes are kept for a user-driven retry. */
    Terminal,
}

/**
 * Whether a failed upload attempt is worth repeating (issue #23).
 *
 * Delegates the status taxonomy to `isRetryableFailure` rather than restating
 * it — that function exists because two screens grew disagreeing copies of
 * the same rule, and a third copy here would have been the same mistake. It
 * is the reason a 408, 429 or 5xx now waits instead of losing the upload,
 * and the reason a 403 or a 507 doesn't retry: the first is the server saying
 * the request itself is wrong, the second needs the *server* to change first.
 *
 * [runAttemptCount] backstops it the way [MAX_FLUSH_ATTEMPTS] backstops the
 * edit queue, and for the same reason: WorkManager applies no cap of its own,
 * so a retryable arm alone would retry on an exponential backoff for as long
 * as the app stayed installed — invisibly, because only a settled row puts
 * anything on screen.
 */
internal fun uploadFailureAction(
    result: ApiResult<*>,
    runAttemptCount: Int,
): UploadFailureAction =
    when {
        runAttemptCount >= MAX_UPLOAD_ATTEMPTS -> UploadFailureAction.Terminal
        isRetryableFailure(result) -> UploadFailureAction.RetryLater
        else -> UploadFailureAction.Terminal
    }

/**
 * Attempts to spend on one upload before marking it failed. Matches
 * [MAX_FLUSH_ATTEMPTS]: WorkManager's backoff is exponential and capped at
 * five hours, so ten attempts is already several days of trying.
 */
internal const val MAX_UPLOAD_ATTEMPTS = 10
