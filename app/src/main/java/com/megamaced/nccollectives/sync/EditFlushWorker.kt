package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drains [com.megamaced.nccollectives.data.db.entity.EditQueueEntity] rows
 * left behind when saves couldn't reach the server. For each queued edit:
 *
 *  - refetch the page over WebDAV (cheap — it returns the ETag in the
 *    headers along with the body) so we have the current server ETag
 *  - if the server ETag still matches the `baseEtag` we held when the user
 *    edited, PUT the queued body and clear the row
 *  - if it doesn't match, the server has moved on; persist the user's body
 *    as a draft on the page (server wins) and mark the queue row as
 *    `CONFLICTED` for the UI to address
 *  - if the failure can never be fixed by trying again — the page was
 *    deleted server-side, write permission was revoked, the attempt cap is
 *    exhausted — do the same thing: park the row as `CONFLICTED` with the
 *    text kept as a draft (see [flushFailureAction])
 *
 * The invariant across every arm is that an edit we stop working on stays
 * recoverable: either the row is left `PENDING` for a later run, or the
 * user's text is on the page row as `draftBodyMd` — which is what
 * `PageViewScreen` renders the `ConflictBanner` from — with the queue row
 * marked `CONFLICTED` so `pendingEntries` stops selecting it.
 */
@HiltWorker
class EditFlushWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val pageDao: PageDao,
        private val editQueueDao: EditQueueDao,
        private val bodyService: PageBodyService,
        private val database: NcCollectivesDatabase,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val entries = editQueueDao.pendingEntries()
            if (entries.isEmpty()) return Result.success()

            var retry = false
            for (entry in entries) {
                editQueueDao.setStatus(entry.pageId, "IN_FLIGHT")
                val page = pageDao.getById(entry.pageId)
                if (page == null) {
                    // The page disappeared locally (collective removed?). Drop
                    // the queue row — nothing to flush.
                    editQueueDao.deleteForPage(entry.pageId)
                    continue
                }

                // B-46: force-write entries (replaceWithDraft path) skip the
                // etag preflight entirely. The user explicitly chose to
                // clobber the server with their draft; refetching just to
                // re-check the etag they've already overridden is wasted IO
                // and risks the worker silently turning their override into
                // a conflict.
                if (!entry.forceWrite) {
                    val currentServer = bodyService.fetchBody(
                        collectivePath = page.collectivePath,
                        filePath = page.filePath,
                        fileName = page.fileName,
                    )
                    val currentEtag = when (currentServer) {
                        is ApiResult.Success -> {
                            currentServer.data.etag
                        }

                        is ApiResult.NetworkError -> {
                            editQueueDao.setStatus(entry.pageId, "PENDING")
                            retry = true
                            continue
                        }

                        ApiResult.Unauthorised -> {
                            editQueueDao.setStatus(entry.pageId, "PENDING")
                            return Result.success() // SessionManager surfaces re-auth
                        }

                        else -> {
                            Timber.w("Flush preflight failed for page %d: %s", entry.pageId, currentServer)
                            when (flushFailureAction(httpStatusOf(currentServer), runAttemptCount)) {
                                FlushFailureAction.Terminal -> {
                                    parkAsConflict(entry)
                                }

                                FlushFailureAction.RetryLater -> {
                                    editQueueDao.setStatus(entry.pageId, "PENDING")
                                    retry = true
                                }
                            }
                            continue
                        }
                    }
                    // B-61: fail closed. The guard used to be
                    // `entry.baseEtag != null && currentEtag != entry.baseEtag`,
                    // so a null `baseEtag` — what we store whenever the
                    // server, or a proxy in front of it, omits the `ETag`
                    // response header — skipped conflict detection *and* then
                    // sent the PUT with no `If-Match` (saveBody treats a null
                    // etag as "no precondition"), i.e. a blind overwrite of
                    // whatever anyone else wrote in the meantime.
                    //
                    // Null on both sides is the one case we can't decide.
                    // There is no `baseBodyMd` column, so there is nothing to
                    // compare the fetched markdown against, and parking the
                    // edit would strand a genuinely-unchanged page in a
                    // conflict it can never leave — so we proceed. Residual
                    // risk: against an ETag-less server a concurrent edit is
                    // still overwritten. Closing that properly means teaching
                    // the queue row to remember the body the edit was based
                    // on, which is a schema change.
                    if (currentEtag != entry.baseEtag) {
                        // Server moved on. Server wins; keep the user's
                        // body as a draft and flag the row.
                        pageDao.updateBody(
                            entry.pageId,
                            currentServer.data.markdown,
                            currentEtag,
                            System.currentTimeMillis(),
                        )
                        parkAsConflict(entry)
                        Timber.i("Edit on page %d conflicted; kept local draft", entry.pageId)
                        continue
                    }
                }

                val putResult = bodyService.saveBody(
                    collectivePath = page.collectivePath,
                    filePath = page.filePath,
                    fileName = page.fileName,
                    body = entry.newBodyMd,
                    // B-46: force-write entries bypass `If-Match`. The
                    // saveBody implementation already treats null as "skip
                    // the precondition header".
                    baseEtag = if (entry.forceWrite) null else entry.baseEtag,
                )
                // B-64: the write has left the device — record what happened
                // to it even if we're being cancelled. Nothing durable exists
                // between `saveBody` returning and these DB writes, so a
                // cancellation landing in that window (logout's `cancelAll`,
                // WorkManager stopping the worker, or the foreground
                // re-enqueue that used to `REPLACE` this work) leaves a body
                // the server accepted with no local record of it — and the
                // next run then reports a conflict against the user's own
                // successful write.
                val outcome = withContext(NonCancellable) { recordPutOutcome(entry, putResult) }
                when (outcome) {
                    FlushRowOutcome.Settled -> Unit
                    FlushRowOutcome.RetryLater -> retry = true
                    FlushRowOutcome.SessionGone -> return Result.success()
                }
            }
            return if (retry) Result.retry() else Result.success()
        }

        /** Persist the result of the PUT for one row. Runs `NonCancellable`. */
        private suspend fun recordPutOutcome(
            entry: EditQueueEntity,
            result: ApiResult<String?>,
        ): FlushRowOutcome =
            when (result) {
                is ApiResult.Success -> {
                    // Issue #18: `entry` is a snapshot taken before the PUT,
                    // and the user can save again while the row is
                    // `IN_FLIGHT` — `saveBody` upserts a newer body over it.
                    // Deleting the row on the strength of the snapshot would
                    // discard an edit the server has never seen, so the row
                    // is re-read under a transaction and only dropped if it
                    // still holds what we sent.
                    database.withTransaction {
                        pageDao.updateBody(
                            entry.pageId,
                            entry.newBodyMd,
                            result.data,
                            System.currentTimeMillis(),
                        )
                        pageDao.updateDraft(entry.pageId, null)
                        val survivor = settledQueueRow(
                            current = editQueueDao.forPage(entry.pageId),
                            flushedBody = entry.newBodyMd,
                            newEtag = result.data,
                        )
                        if (survivor == null) {
                            editQueueDao.deleteForPage(entry.pageId)
                            FlushRowOutcome.Settled
                        } else {
                            editQueueDao.upsert(survivor)
                            // There is still unsent work for this page, and
                            // this run is past it.
                            FlushRowOutcome.RetryLater
                        }
                    }
                }

                ApiResult.Conflict -> {
                    // B-46: even a force-write can race against another
                    // writer; rather than overwriting the user's draft
                    // (which `replaceWithDraft` may have just refreshed),
                    // surface the conflict. The user resolves via the
                    // banner; the queue row is left as CONFLICTED.
                    if (entry.forceWrite) {
                        editQueueDao.setStatus(entry.pageId, "CONFLICTED")
                    } else {
                        parkAsConflict(entry)
                    }
                    FlushRowOutcome.Settled
                }

                is ApiResult.NetworkError -> {
                    editQueueDao.setStatus(entry.pageId, "PENDING")
                    FlushRowOutcome.RetryLater
                }

                ApiResult.Unauthorised -> {
                    editQueueDao.setStatus(entry.pageId, "PENDING")
                    FlushRowOutcome.SessionGone
                }

                is ApiResult.HttpError -> {
                    Timber.w("Flush HTTP %d for page %d: %s", result.code, entry.pageId, result.message)
                    settleFailure(entry, result)
                }

                is ApiResult.Unexpected -> {
                    Timber.w(result.cause, "Flush unexpected error for page %d", entry.pageId)
                    settleFailure(entry, result)
                }
            }

        private suspend fun settleFailure(
            entry: EditQueueEntity,
            result: ApiResult<*>,
        ): FlushRowOutcome =
            when (flushFailureAction(httpStatusOf(result), runAttemptCount)) {
                FlushFailureAction.Terminal -> {
                    parkAsConflict(entry)
                    FlushRowOutcome.Settled
                }

                FlushFailureAction.RetryLater -> {
                    editQueueDao.setStatus(entry.pageId, "PENDING")
                    FlushRowOutcome.RetryLater
                }
            }

        /**
         * Give up on [entry] without discarding what the user wrote: the text
         * lands on the page row as a draft (which is what raises the
         * `ConflictBanner`) and the queue row is parked so `pendingEntries`
         * stops handing it back. "Conflicted" is a slightly generous label
         * for a 403 or a 404, but it is the one state the UI already knows
         * how to offer a way out of — copy, discard, or force-replace.
         */
        private suspend fun parkAsConflict(entry: EditQueueEntity) {
            database.withTransaction {
                // Issue #18: park the body the row holds *now*, not the one
                // this run snapshotted. A save that landed while the PUT was
                // in flight replaced it with a newer body containing the
                // user's latest work, and `CONFLICTED` rows are read by
                // nothing — parking the snapshot would strand that work
                // where neither the editor nor the banner can reach it.
                val latest = editQueueDao.forPage(entry.pageId)?.newBodyMd ?: entry.newBodyMd
                pageDao.updateDraft(entry.pageId, latest)
                editQueueDao.setStatus(entry.pageId, "CONFLICTED")
            }
            Timber.w("Giving up on the queued edit for page %d; kept it as a draft", entry.pageId)
        }
    }

/**
 * The queue row to leave behind after a PUT the server accepted, or null if
 * the row can be deleted.
 *
 * Issue #18: [current] is re-read after the PUT because a save landing while
 * the row was `IN_FLIGHT` upserts a newer body over it. A surviving edit is
 * re-based on [newEtag] — it was authored on top of the body just written,
 * so that body's ETag, not the one the chain started from, is the
 * precondition it should be flushed against. Leaving the stale `baseEtag`
 * would guarantee the next flush reported a conflict against the user's own
 * successful write.
 */
internal fun settledQueueRow(
    current: EditQueueEntity?,
    flushedBody: String,
    newEtag: String?,
): EditQueueEntity? =
    when {
        current == null -> null
        current.newBodyMd == flushedBody -> null
        else -> current.copy(baseEtag = newEtag, status = "PENDING")
    }

/** What [EditFlushWorker] does with one row after an attempt failed. */
internal enum class FlushRowOutcome {
    /** Nothing more to do for this row on any run. */
    Settled,

    /** Row left `PENDING`; ask WorkManager for another run. */
    RetryLater,

    /** The session is gone — stop the whole drain, `SessionManager` owns it. */
    SessionGone,
}

/** Whether a failed flush attempt is worth repeating. */
internal enum class FlushFailureAction {
    RetryLater,
    Terminal,
}

/**
 * B-62: classify a failed flush attempt instead of retrying everything
 * forever.
 *
 * Both failure arms used to set the row back to `PENDING` and return
 * `Result.retry()`. WorkManager applies no attempt cap of its own, so a
 * permanent failure — the page deleted server-side (404), edit rights
 * revoked (403), the file locked by another client (423), the quota full
 * (507) — retried on an exponential backoff capped at five hours, for as
 * long as the app stayed installed. Worse, it did so invisibly: only
 * `CONFLICTED` rows put anything on screen, so the user's edit was neither
 * saved nor surfaced.
 *
 * This mirrors the policy `SyncWorker.retryDecision` documents — "the server
 * answered and refused, which the same request won't fix" — and what
 * `AttachmentUploadWorker` already does with an `HttpError` (mark the row
 * failed rather than loop). It diverges from `SyncWorker` on 5xx: that
 * worker gives up because a GET has nothing to lose and the next foreground
 * run repeats it anyway, whereas here the row *is* the user's only copy of
 * their edit, so a 502/503 behind a reverse proxy is worth waiting out.
 * [MAX_FLUSH_ATTEMPTS] is what stops that from being unbounded, and it
 * backstops every other arm — including the ones with no HTTP status at all
 * (an `Unexpected`, e.g. a WebDAV URL that can't be built).
 *
 * 408 and 429 are 4xx by number and transient by meaning: request timeout
 * and rate limiting are exactly the cases retrying is *for*.
 */
internal fun flushFailureAction(
    httpCode: Int?,
    runAttemptCount: Int,
): FlushFailureAction =
    when {
        runAttemptCount >= MAX_FLUSH_ATTEMPTS -> FlushFailureAction.Terminal
        httpCode == null -> FlushFailureAction.RetryLater
        httpCode == 408 || httpCode == 429 -> FlushFailureAction.RetryLater
        httpCode in 400..499 -> FlushFailureAction.Terminal
        httpCode == 507 -> FlushFailureAction.Terminal
        else -> FlushFailureAction.RetryLater
    }

/**
 * Attempts to spend on one queued edit before parking it as conflicted.
 * WorkManager's backoff is exponential and capped at five hours, so ten
 * attempts is already several days of trying.
 *
 * Counted per work request, not per row: a fresh `flushEditsWhenOnline()`
 * appends a new request whose `runAttemptCount` starts at zero. That is why
 * the status-based arms above carry the real weight — this only bounds the
 * failures that arrive with no status to classify.
 */
internal const val MAX_FLUSH_ATTEMPTS = 10

/**
 * The HTTP status behind [result], or null when the failure never got one
 * (a dropped connection, or an exception we couldn't classify). `Conflict`
 * is the 412 `webDavCall` folds into its own arm.
 */
internal fun httpStatusOf(result: ApiResult<*>): Int? =
    when (result) {
        is ApiResult.HttpError -> result.code
        ApiResult.Conflict -> 412
        else -> null
    }
