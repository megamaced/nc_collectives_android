package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
                        is ApiResult.Success -> currentServer.data.etag
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
                            editQueueDao.setStatus(entry.pageId, "PENDING")
                            retry = true
                            continue
                        }
                    }
                    if (entry.baseEtag != null && currentEtag != entry.baseEtag) {
                        // Server moved on. Server wins; keep the user's
                        // body as a draft and flag the row.
                        pageDao.updateBody(
                            entry.pageId,
                            currentServer.data.markdown,
                            currentEtag,
                            System.currentTimeMillis(),
                        )
                        pageDao.updateDraft(entry.pageId, entry.newBodyMd)
                        editQueueDao.setStatus(entry.pageId, "CONFLICTED")
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
                when (putResult) {
                    is ApiResult.Success -> {
                        pageDao.updateBody(
                            entry.pageId,
                            entry.newBodyMd,
                            putResult.data,
                            System.currentTimeMillis(),
                        )
                        pageDao.updateDraft(entry.pageId, null)
                        editQueueDao.deleteForPage(entry.pageId)
                    }
                    ApiResult.Conflict -> {
                        // B-46: even a force-write can race against another
                        // writer; rather than overwriting the user's draft
                        // (which `replaceWithDraft` may have just refreshed),
                        // surface the conflict. The user resolves via the
                        // banner; the queue row is left as CONFLICTED.
                        if (!entry.forceWrite) {
                            pageDao.updateDraft(entry.pageId, entry.newBodyMd)
                        }
                        editQueueDao.setStatus(entry.pageId, "CONFLICTED")
                    }
                    is ApiResult.NetworkError -> {
                        editQueueDao.setStatus(entry.pageId, "PENDING")
                        retry = true
                    }
                    ApiResult.Unauthorised -> {
                        editQueueDao.setStatus(entry.pageId, "PENDING")
                        return Result.success()
                    }
                    is ApiResult.HttpError -> {
                        Timber.w("Flush HTTP %d for page %d: %s", putResult.code, entry.pageId, putResult.message)
                        editQueueDao.setStatus(entry.pageId, "PENDING")
                        retry = true
                    }
                    is ApiResult.Unexpected -> {
                        Timber.w(putResult.cause, "Flush unexpected error for page %d", entry.pageId)
                        editQueueDao.setStatus(entry.pageId, "PENDING")
                        retry = true
                    }
                }
            }
            return if (retry) Result.retry() else Result.success()
        }
    }
