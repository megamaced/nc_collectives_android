package com.megamaced.nccollectives.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.auth.bound
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.model.OpenableAttachment
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: CollectivesApiService,
        private val pageDao: PageDao,
        private val attachmentDao: AttachmentDao,
        private val bodyService: PageBodyService,
        private val syncScheduler: SyncScheduler,
        private val database: NcCollectivesDatabase,
        private val accountGeneration: AccountGeneration,
    ) : AttachmentRepository {
        override fun observeForPage(pageId: Long): Flow<List<Attachment>> =
            attachmentDao.observeForPage(pageId).map { rows ->
                if (rows.isEmpty()) {
                    emptyList<Attachment>()
                } else {
                    // R-41: every row in this flow belongs to `pageId`, so the
                    // page row and its attachments directory are constant for
                    // the whole emission. Resolving them here instead of inside
                    // `remoteUrlFor` collapses one `SELECT *` on `pages` per
                    // attachment — each dragging the full cached markdown body
                    // along — into a single read per emission.
                    val page = pageDao.getById(pageId)
                    val dir = attachmentsDirectoryFor(pageId)
                    rows.map { entity -> entity.toDomain(remoteUrlFor(entity, page, dir)) }
                }
            }

        // Bound from before the row is read, as everything here that reads
        // the cache and then sends or writes what it read: see
        // `AccountGeneration.bound`.
        override suspend fun refresh(pageId: Long): ApiResult<Unit> =
            accountGeneration.bound { generation -> refreshBound(pageId, generation) }

        private suspend fun refreshBound(
            pageId: Long,
            generation: Long,
        ): ApiResult<Unit> {
            val page = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            // OCS-3: typed JSON list replaces the WebDAV PROPFIND + XML
            // parse in Batch 12. The server's `id` field is the stable
            // attachment id used by [delete] (OCS-4).
            val result = apiCall { api.listAttachments(page.collectiveId, pageId) }
            return when (result) {
                is ApiResult.Success -> {
                    val now = System.currentTimeMillis()
                    // B-43: upsert + reconcile under one transaction so
                    // observers don't see an intermediate union. B-36:
                    // protect in-flight PENDING/UPLOADING rows from being
                    // clobbered by a server-side row with the same key —
                    // if the user just queued an upload, the worker still
                    // owns that row; replacing it with status=REMOTE +
                    // localUriString=null would orphan the staged bytes.
                    val recorded = writeUnder(generation) {
                        val existing = attachmentDao
                            .listForPage(pageId)
                            .associateBy { it.id }
                        val remoteEntities = result.data.ocs.data.attachments.map { dto ->
                            val key = AttachmentEntity.key(pageId, dto.name)
                            val current = existing[key]
                            if (current != null && current.status != AttachmentEntity.STATUS_REMOTE) {
                                // B-36: preserve the local row, but catch up
                                // the server-id so a subsequent OCS-4 delete
                                // can target it correctly.
                                //
                                // Issue #36: *every* non-REMOTE status, not
                                // just PENDING and UPLOADING. A row this
                                // device owns describes an operation in
                                // progress, and overwriting it with the
                                // server's view discards that operation. It
                                // mattered once #23 gave a FAILED row staged
                                // bytes and a Retry button: a server file of
                                // the same name — another client's, or this
                                // upload's own lost-response twin — replaced
                                // the row with `status = REMOTE` and
                                // `localUriString = null`, so the retry
                                // affordance vanished and the staged bytes
                                // were left with nothing pointing at them.
                                // `AttachmentsViewModel` refreshes on screen
                                // open, so merely reopening the screen did
                                // it. DELETING is protected by the same
                                // clause, which is what stops a refresh
                                // resurrecting a tombstoned upload.
                                current.copy(serverAttachmentId = dto.id)
                            } else {
                                AttachmentEntity(
                                    id = key,
                                    pageId = pageId,
                                    fileName = dto.name,
                                    contentType = dto.mimetype,
                                    size = dto.filesize,
                                    // OCS returns seconds since epoch; Room/UI use millis.
                                    lastModifiedMs = dto.timestamp * 1000L,
                                    // OCS doesn't return an ETag, and a listed
                                    // attachment is deleted by its server id, so
                                    // none is needed.
                                    etag = null,
                                    status = AttachmentEntity.STATUS_REMOTE,
                                    localUriString = null,
                                    lastSyncedAt = now,
                                    serverAttachmentId = dto.id,
                                )
                            }
                        }
                        attachmentDao.upsertAll(remoteEntities)
                        // B-42: short-circuit on empty keep-list. The
                        // `deleteMissingRemoteForPage` query already filters
                        // to `status = 'REMOTE'`, so pending/uploading rows
                        // are preserved either way — but `WHERE id NOT IN ()`
                        // is a SQLite syntax error on an empty list, so
                        // route through `deleteRemoteForPage` when there's
                        // nothing to keep.
                        val keepIds = remoteEntities.map { it.id }
                        if (keepIds.isEmpty()) {
                            attachmentDao.deleteRemoteForPage(pageId)
                        } else {
                            attachmentDao.deleteMissingRemoteForPage(pageId, keepIds)
                        }
                    }
                    if (recorded) ApiResult.Success(Unit) else accountChanged()
                }

                // Issue #26: `mapSuccess` rather than the hand-rolled
                // `mapNonSuccess` this used to call. That function spelled
                // out all six arms including an `is ApiResult.Success` one
                // that could never run, since this is the `else` of a `when`
                // whose first arm already took `Success` — and re-typing a
                // failure is exactly what `mapSuccess` does.
                else -> {
                    result.mapSuccess { }
                }
            }
        }

        override suspend fun enqueueUpload(
            pageId: Long,
            sourceUri: Uri,
            suggestedFileName: String,
            contentType: String?,
        ): String? =
            accountGeneration.bound { generation ->
                // The staging copy is this call's own from the moment it
                // exists. Whatever ends the call — a refusal, a failed copy,
                // cancellation part way through it — deletes it here, unless
                // it was moved to the row's name, after which this path
                // names nothing.
                val copy = AtomicReference<File?>()
                try {
                    enqueueUploadBound(pageId, sourceUri, suggestedFileName, contentType, generation, copy::set)
                } finally {
                    copy.get()?.delete()
                }
            }

        private suspend fun enqueueUploadBound(
            pageId: Long,
            sourceUri: Uri,
            suggestedFileName: String,
            contentType: String?,
            generation: Long,
            ownCopy: (File) -> Unit,
        ): String? {
            val resolvedName = resolveCollisionFreeName(pageId, suggestedFileName)
            val resolvedType = contentType ?: guessMimeType(resolvedName)
            // B-29: copy the picked/shared bytes into our own cache before
            // returning. Photo-picker URIs aren't persistable, the sender
            // may revoke FLAG_GRANT_READ_URI_PERMISSION at any time, and
            // the OS may evict the photo-picker cache before the worker
            // runs — copying here is the only way to guarantee the worker
            // can still read the bytes on the other side of process death.
            val copy = copyToStaging(sourceUri, ownCopy) ?: return null
            val key = AttachmentEntity.key(pageId, resolvedName)
            val stagedFile = stagedFileFor(context, key)
            val entity = AttachmentEntity(
                id = key,
                pageId = pageId,
                fileName = resolvedName,
                contentType = resolvedType,
                size = copy.length(),
                lastModifiedMs = System.currentTimeMillis(),
                etag = null,
                status = AttachmentEntity.STATUS_PENDING,
                localUriString = Uri.fromFile(stagedFile).toString(),
                lastSyncedAt = System.currentTimeMillis(),
            )
            // The copy is this call's own file until it is moved to the
            // row's name, and the move and the insert happen together under
            // the generation. After a switch the row would have the worker
            // upload these bytes to the next account's page under the same
            // id, and the name may be a staged file of the next account's.
            // Inside the transaction, the wipe that a switch runs can't
            // clear the cache until the move has been made or skipped.
            var moved = false
            val recorded = writeUnder(generation) {
                moved = copy.renameTo(stagedFile)
                if (moved) attachmentDao.upsert(entity)
            }
            if (!recorded || !moved) return null
            syncScheduler.flushAttachmentUploadsWhenOnline()
            // S-15: camera captures and any other own-FileProvider sources
            // are now redundant — the bytes live in the staged copy and
            // the worker reads from there. Drop the original so the
            // capture cache doesn't accumulate over the install lifetime.
            deleteIfOwnFileProvider(sourceUri)
            return resolvedName
        }

        private fun deleteIfOwnFileProvider(sourceUri: Uri) {
            if (sourceUri.scheme != "content") return
            if (sourceUri.authority != "${context.packageName}.fileprovider") return
            // Map back to the underlying file. The FileProvider authority's
            // `<cache-path name="captures" path="attachments/" />` exposes
            // `cacheDir/attachments/<name>` as `content://…/captures/<name>`.
            val segments = sourceUri.pathSegments
            if (segments.size < 2 || segments[0] != "captures") return
            val name = segments.drop(1).joinToString("/")
            val file = File(File(context.cacheDir, "attachments"), name)
            if (file.exists() && !file.delete()) {
                Timber.w("Couldn't delete capture source %s", file.absolutePath)
            }
        }

        /**
         * Copy [sourceUri] into a new file in the staging directory, handed
         * to [own] as soon as it exists, before anything can fail or be
         * cancelled. The caller moves it to the row's name, which is keyed
         * the same as the row so the worker can find and delete it without
         * an extra Room read, and deletes it otherwise.
         */
        private suspend fun copyToStaging(
            sourceUri: Uri,
            own: (File) -> Unit,
        ): File? =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "attachments-pending").apply { mkdirs() }
                val staged = File.createTempFile("staging-", ".part", dir).also(own)
                try {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    staged
                } catch (e: SecurityException) {
                    Timber.w(e, "Source URI %s not readable for staging", sourceUri)
                    null
                } catch (e: java.io.IOException) {
                    Timber.w(e, "Failed to stage %s", sourceUri)
                    null
                }
            }

        override suspend fun delete(
            pageId: Long,
            fileName: String,
        ): ApiResult<Unit> = accountGeneration.bound { generation -> deleteBound(pageId, fileName, generation) }

        private suspend fun deleteBound(
            pageId: Long,
            fileName: String,
            generation: Long,
        ): ApiResult<Unit> {
            val page = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val key = AttachmentEntity.key(pageId, fileName)
            val existing = attachmentDao.getById(key)
            // Issue #56: a DELETE goes by name, and nothing proves the
            // object at that name is this device's unless an upload of it
            // was answered with success — which would have made the row
            // REMOTE. A PUT refused because the name was taken, and whose
            // answer was lost, looks the same from here as one that landed;
            // the file there may be another client's, hidden by a stale
            // listing. So a waiting or failed upload is dropped where it
            // stands, row and staged bytes. If its bytes did land, the next
            // listing shows them, and they can be deleted by their server id.
            var dropped = false
            if (!writeUnder(generation) {
                    dropped = attachmentDao.deleteUnclaimed(key) == 1
                    if (dropped) stagedFileFor(context, key).delete()
                }
            ) {
                return accountChanged()
            }
            if (dropped) return ApiResult.Success(Unit)
            if (deleteRoute(existing) == DeleteRoute.Tombstone) {
                // Issue #35: a local-only delete could not honour the
                // request. Dropping the Room row neither cancels a PUT
                // already on the wire nor undoes one that has landed, so the
                // bytes stayed on the server and the next listing inserted
                // them again as REMOTE — an attachment visibly resurrecting
                // after the UI said it was deleted.
                //
                // So a row a worker holds becomes a tombstone: hidden from
                // the grid, and left for `AttachmentUploadWorker`, which
                // removes the file only if the upload is answered with
                // success, and otherwise drops the tombstone (issue #56).
                // Under the generation: a tombstone on the next account's row
                // under the same key would have the worker delete its file.
                if (!writeUnder(generation) { attachmentDao.setStatus(key, AttachmentEntity.STATUS_DELETING) }) return accountChanged()
                syncScheduler.flushAttachmentUploadsWhenOnline()
                return ApiResult.Success(Unit)
            }
            // OCS-4: delete by server-assigned id (Batch 18j). Replaces the
            // previous WebDAV DELETE by user-typed filename. If the row
            // doesn't have a serverId cached yet (e.g. user uploaded but
            // hasn't refreshed the attachments screen since), refresh
            // inline to populate it before deleting.
            var serverId = existing?.serverAttachmentId
            if (serverId == null) {
                val refreshed = refreshBound(pageId, generation)
                if (refreshed !is ApiResult.Success) return refreshed
                serverId = attachmentDao.getById(key)?.serverAttachmentId
                    ?: return ApiResult.Unexpected(
                        IllegalStateException("Attachment $fileName not found on server"),
                    )
            }
            val result = apiCall { api.deleteAttachment(page.collectiveId, pageId, serverId) }
            if (result is ApiResult.Success && !writeUnder(generation) { attachmentDao.delete(key) }) return accountChanged()
            return result
        }

        override suspend fun retryUpload(
            pageId: Long,
            fileName: String,
        ): ApiResult<Unit> = accountGeneration.bound { generation -> retryUploadBound(pageId, fileName, generation) }

        private suspend fun retryUploadBound(
            pageId: Long,
            fileName: String,
            generation: Long,
        ): ApiResult<Unit> {
            val key = AttachmentEntity.key(pageId, fileName)
            val existing = attachmentDao.getById(key)
                ?: return ApiResult.Unexpected(IllegalStateException("Attachment $fileName not found"))
            if (existing.status == AttachmentEntity.STATUS_REMOTE) return ApiResult.Success(Unit)
            if (existing.status == AttachmentEntity.STATUS_DELETING) {
                // Not reachable from the grid, which hides tombstones — but
                // re-arming one as PENDING would upload bytes the user asked
                // to be rid of (issue #35).
                return ApiResult.Unexpected(IllegalStateException("$fileName is being deleted"))
            }
            val staged = stagedFileFor(context, key)
            if (!staged.exists() || staged.length() == 0L) {
                // Better to say so than to re-queue a row the worker will
                // fail again for a reason the user can't see. The cache
                // directory is evictable, and the two worker arms that drop
                // the staging copy do it because there was nothing to keep.
                return ApiResult.Unexpected(
                    IllegalStateException("The staged copy of $fileName is no longer on the device"),
                )
            }
            // Issue #30: a fresh budget. The user asked for this attempt
            // explicitly, so it is not a continuation of the ones that failed.
            if (!writeUnder(generation) { attachmentDao.rearmForRetry(key) }) return accountChanged()
            syncScheduler.flushAttachmentUploadsWhenOnline()
            return ApiResult.Success(Unit)
        }

        override suspend fun resolveDeletion(
            pageId: Long,
            fileName: String,
        ): ApiResult<Unit> = accountGeneration.bound { generation -> resolveDeletionBound(pageId, fileName, generation) }

        private suspend fun resolveDeletionBound(
            pageId: Long,
            fileName: String,
            generation: Long,
        ): ApiResult<Unit> {
            val key = AttachmentEntity.key(pageId, fileName)
            // Issue #56: a DELETE goes by name, so it needs proof that the
            // bytes there are this device's — the ETag the server answered
            // the upload with (`confirmLanded`). Without it the upload was
            // never sent, or never answered with success: refused, perhaps,
            // because the name was another client's. That tombstone is
            // dropped here, and nothing is sent.
            var unconfirmed = false
            if (!writeUnder(generation) {
                    unconfirmed = attachmentDao.deleteUnconfirmedTombstone(key) == 1
                    if (unconfirmed) deleteStagedFile(key)
                }
            ) {
                return accountChanged()
            }
            if (unconfirmed) return ApiResult.Success(Unit)
            val landed = attachmentDao
                .getById(key)
                ?.takeIf { it.status == AttachmentEntity.STATUS_DELETING }
                ?.etag
                ?: return ApiResult.Success(Unit)
            val page = pageDao.getById(pageId)
            if (page == null) {
                // The page went with its collective. Nothing reachable to
                // delete, and the cascade already dropped the sibling rows.
                return if (dropTombstone(key, generation)) ApiResult.Success(Unit) else accountChanged()
            }
            val result = bodyService.deleteFile(
                collectivePath = page.collectivePath,
                filePath = combinePath(page.filePath, attachmentsDirectoryFor(pageId)),
                fileName = fileName,
                ifMatch = landed,
            )
            // `deleteFile` counts 404 as success, so reaching here with a
            // Success means the object is gone whether or not it ever
            // existed. A 412 means the file there is no longer the upload —
            // replaced since, and not this device's to remove. Anything else
            // keeps the tombstone for another run.
            if (result is ApiResult.Success || result == ApiResult.Conflict) {
                return if (dropTombstone(key, generation)) ApiResult.Success(Unit) else accountChanged()
            }
            return result
        }

        /** Drop the tombstone [key] while [generation] is current, and say whether it went. */
        private suspend fun dropTombstone(
            key: String,
            generation: Long,
        ): Boolean {
            // Issue #23: and the bytes behind it. Dropping only the row
            // orphaned the staging copy — invisible to the UI, still on disk,
            // and now that a FAILED row keeps its bytes on purpose this is
            // the path that has to collect them. In the same transaction, as
            // in [enqueueUpload]: after a switch the name may be the next
            // account's.
            return writeUnder(generation) {
                attachmentDao.delete(key)
                deleteStagedFile(key)
            }
        }

        override suspend fun renameForRemoteCollision(
            pageId: Long,
            fileName: String,
        ): String? = accountGeneration.bound { generation -> renameForRemoteCollisionBound(pageId, fileName, generation) }

        private suspend fun renameForRemoteCollisionBound(
            pageId: Long,
            fileName: String,
            generation: Long,
        ): String? {
            val oldKey = AttachmentEntity.key(pageId, fileName)
            attachmentDao.getById(oldKey) ?: return null
            // Best-effort: a fresh listing is what lets the local resolver
            // step past the names the server already holds, rather than
            // handing back one it will refuse for the same reason. Offline,
            // or on a failure, the resolver still bumps past our own row and
            // the next 412 brings us back here.
            refreshBound(pageId, generation)
            val newName = resolveCollisionFreeName(pageId, fileName)
            val newKey = AttachmentEntity.key(pageId, newName)
            if (newKey == oldKey) return null
            // The staged bytes are keyed on the row id, so the file moves
            // with the row. Bail rather than re-queue if it can't: a PENDING
            // row with no bytes behind it just fails again.
            val oldStaged = stagedFileFor(context, oldKey)
            val newStaged = stagedFileFor(context, newKey)
            // The file and the row move together, under the generation, as
            // in [enqueueUpload]: after a switch both names may be the next
            // account's.
            var moved = false
            var cancelled = false
            val recorded = writeUnder(generation) {
                // Issue #63: the user may have cancelled it while its upload
                // was out. A 412 means its bytes never landed — the name was
                // someone else's — so there is nothing on the server to undo,
                // and a tombstone's DELETE would remove that other file.
                // Dropped here, not re-queued under a new name.
                val current = attachmentDao.getById(oldKey)
                if (current == null || current.status == AttachmentEntity.STATUS_DELETING) {
                    cancelled = true
                    attachmentDao.delete(oldKey)
                    oldStaged.delete()
                    return@writeUnder
                }
                moved = oldStaged.exists() && oldStaged.renameTo(newStaged)
                if (!moved) return@writeUnder
                attachmentDao.delete(oldKey)
                attachmentDao.upsert(
                    current.copy(
                        id = newKey,
                        fileName = newName,
                        status = AttachmentEntity.STATUS_PENDING,
                        localUriString = Uri.fromFile(newStaged).toString(),
                        // A new filename is a new attempt chain (issue #30).
                        attempts = 0,
                        // The old row's server id, if it had one, belonged to
                        // whatever is sitting at the old name.
                        serverAttachmentId = null,
                    ),
                )
            }
            if (!recorded) return null
            if (cancelled) {
                Timber.i("Attachment %s was cancelled; its name was taken, so nothing landed", oldKey)
                return null
            }
            if (!moved) {
                Timber.w("Couldn't move the staged copy of %s aside", oldKey)
                return null
            }
            Timber.i("Attachment name %s was taken on the server; re-queued as %s", fileName, newName)
            return newName
        }

        /** [NcCollectivesDatabase.writeUnder] on this repository's cache. */
        private suspend fun writeUnder(
            generation: Long,
            writes: suspend () -> Unit,
        ): Boolean = database.writeUnder(accountGeneration, generation, writes)

        private fun deleteStagedFile(attachmentId: String) {
            val staged = stagedFileFor(context, attachmentId)
            if (staged.exists() && !staged.delete()) {
                Timber.w("Couldn't delete the staged copy at %s", staged.absolutePath)
            }
        }

        override suspend fun rekeyForPage(
            oldPageId: Long,
            newPageId: Long,
        ) {
            if (oldPageId == newPageId) return
            for (row in attachmentDao.listForPage(oldPageId)) {
                val newKey = AttachmentEntity.key(newPageId, row.fileName)
                attachmentDao.delete(row.id)
                // A `REMOTE` row carries no local bytes and the next
                // PROPFIND rebuilds it anyway, so there is nothing to move
                // and nothing lost if the move is imperfect. The unfinished
                // statuses are the ones worth carrying: their staged copy is
                // the only place those bytes exist.
                if (row.localUriString == null) {
                    attachmentDao.upsert(row.copy(id = newKey, pageId = newPageId))
                    continue
                }
                val oldStaged = stagedFileFor(context, row.id)
                val newStaged = stagedFileFor(context, newKey)
                if (!oldStaged.exists() || !oldStaged.renameTo(newStaged)) {
                    // Same posture as `renameForRemoteCollision`: a queued
                    // row with no bytes behind it only fails again, and now
                    // in a way the user can't act on.
                    Timber.w("Couldn't carry the staged copy of %s to page %d", row.id, newPageId)
                    continue
                }
                attachmentDao.upsert(
                    row.copy(
                        id = newKey,
                        pageId = newPageId,
                        localUriString = Uri.fromFile(newStaged).toString(),
                        // The server id belonged to a directory addressed by
                        // the old page id.
                        serverAttachmentId = null,
                    ),
                )
            }
        }

        override suspend fun downloadForViewing(
            pageId: Long,
            relativePath: String,
        ): ApiResult<OpenableAttachment> =
            accountGeneration.bound { generation ->
                downloadForViewingBound(pageId, relativePath, generation)
            }

        private suspend fun downloadForViewingBound(
            pageId: Long,
            relativePath: String,
            generation: Long,
        ): ApiResult<OpenableAttachment> {
            val page = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val segments = relativePath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                return ApiResult.Unexpected(IllegalArgumentException("Empty attachment path"))
            }
            // S-14′ again, on this side of the boundary: `parseAttachmentRef`
            // already refuses traversal, but the attachments *screen* builds
            // a path from a server-supplied filename. A `..` segment would
            // otherwise stage the download outside the cache directory.
            if (segments.any { it == ".." }) {
                return ApiResult.Unexpected(
                    IllegalArgumentException("Refusing attachment path with a traversal segment"),
                )
            }
            val fileName = segments.last()
            // Directory part of the ref joined onto the page's own filePath.
            // `buildWebDavUrl` runs every segment through
            // `ServerStringValidation.cleanPathSegment`, so a hostile ref
            // still can't walk out of the user's Files tree (S-14′).
            val refDir = segments.dropLast(1).joinToString("/")
            val filePath = if (refDir.isEmpty()) page.filePath else combinePath(page.filePath, refDir)

            val target = viewCacheFileFor(context, generation, pageId, fileName)
            target.parentFile?.mkdirs()
            val result = bodyService.downloadTo(
                collectivePath = page.collectivePath,
                filePath = filePath,
                fileName = fileName,
                target = target,
            )
            if (result !is ApiResult.Success) {
                // Don't leave a truncated file behind to be handed to a
                // viewer app on the next tap. Unchecked cast is the same
                // idiom `ApiResult.mapSuccess` documents — every non-Success
                // arm is `ApiResult<Nothing>`.
                target.delete()
                @Suppress("UNCHECKED_CAST")
                return result as ApiResult<OpenableAttachment>
            }
            // Not handed out once its account has gone: the file is the
            // outgoing account's, and a viewer opened now would show it in
            // the next account's session.
            if (!accountGeneration.isCurrent(generation)) {
                target.delete()
                return accountChanged()
            }
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
            } catch (e: IllegalArgumentException) {
                // Only fires if the cache dir stops matching
                // `file_provider_paths.xml` — a build-config error rather
                // than a runtime condition, but surface it instead of
                // crashing on the user's tap.
                Timber.e(e, "Staged attachment %s outside FileProvider paths", target)
                target.delete()
                return ApiResult.Unexpected(e)
            }
            return ApiResult.Success(
                OpenableAttachment(
                    uri = uri,
                    fileName = fileName,
                    // Prefer the server's Content-Type, but fall back to the
                    // extension when it answers the generic octet-stream —
                    // the chooser has nothing to match on otherwise.
                    mimeType = result.data?.takeUnless { it == OCTET_STREAM }
                        ?: guessMimeType(fileName),
                ),
            )
        }

        override suspend fun attachmentsBaseUrl(pageId: Long): String? {
            val page = pageDao.getById(pageId) ?: return null
            val dir = attachmentsDirectoryFor(pageId)
            // Same non-throwing contract as [urlFor]: a null base URL makes
            // `absolutizeImageRefs` leave refs relative rather than crashing
            // the page view.
            val withDummy = try {
                bodyService.resourceUrl(
                    collectivePath = page.collectivePath,
                    filePath = combinePath(page.filePath, dir),
                    fileName = "_",
                )
            } catch (_: Exception) {
                return null
            }
            return withDummy.removeSuffix("_")
        }

        /**
         * Remote URL for a REMOTE-status row, given the already-resolved
         * page row and attachments directory (R-41 — the caller hoists both
         * out of the per-row loop).
         *
         * Stays deliberately non-throwing: a malformed server-supplied path
         * must degrade to "no remote url" for that one attachment rather
         * than tear down the whole observing flow.
         */
        private fun remoteUrlFor(
            entity: AttachmentEntity,
            page: PageEntity?,
            attachmentsDir: String,
        ): String? {
            if (entity.status != AttachmentEntity.STATUS_REMOTE) return null
            if (page == null) return null
            return try {
                bodyService.resourceUrl(
                    collectivePath = page.collectivePath,
                    filePath = combinePath(page.filePath, attachmentsDir),
                    fileName = entity.fileName,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * A filename no row for [pageId] is using.
         *
         * Only the *local* table is probed, which is the whole of issue #24:
         * a name free here can be taken on the server, so the upload sends
         * `If-None-Match: *` and [renameForRemoteCollision] comes back here
         * after a 412.
         */
        private suspend fun resolveCollisionFreeName(
            pageId: Long,
            suggested: String,
        ): String {
            val sanitised = sanitiseFileName(suggested)
            var counter = 0
            while (true) {
                val candidate = collisionCandidate(sanitised, counter)
                if (attachmentDao.getById(AttachmentEntity.key(pageId, candidate)) == null) return candidate
                counter++
            }
        }

        private fun sanitiseFileName(name: String): String {
            val cleaned = name
                .filter { ch -> ch.code >= 0x20 && ch !in INVALID_FILENAME_CHARS }
                .trim()
                .trimStart('.') // S-5: refuse `.`, `..`, leading-dot names (`.htaccess` etc.)
                .ifEmpty { FALLBACK_STEM }
            return boundedFileName(cleaned)
        }

        private fun guessMimeType(fileName: String): String? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return if (ext.isEmpty()) {
                null
            } else {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }
        }

        companion object {
            /** Nextcloud Collectives stores per-page attachments here. */
            fun attachmentsDirectoryFor(pageId: Long): String = ".attachments.$pageId"

            fun combinePath(
                base: String,
                child: String,
            ): String = if (base.isEmpty()) child else "$base/$child"

            /**
             * Cache file a *downloaded* attachment is staged in before being
             * handed to another app. Namespaced by page id so two pages with
             * a same-named attachment can't serve each other's bytes, and
             * kept separate from `attachments-pending/` so the upload
             * worker's cleanup can never delete a file a viewer app is
             * currently reading through our FileProvider grant. Namespaced
             * by account [generation] too, so a download that a switch
             * overtakes writes, and on failure deletes, only its own
             * account's file, never the next account's of the same name.
             */
            fun viewCacheFileFor(
                context: Context,
                generation: Long,
                pageId: Long,
                fileName: String,
            ): File =
                File(
                    File(File(File(context.cacheDir, "attachments-view"), "g$generation"), pageId.toString()),
                    fileName.replace('/', '_'),
                )

            /**
             * Delete every cached attachment byte-store: staged uploads and
             * files downloaded for viewing.
             *
             * Called from the sign-out flow. Both directories hold raw user
             * file content that no Room row points at once the tables are
             * wiped, so leaving them behind would keep one account's
             * documents readable on disk during the next account's session —
             * the same concern that made the Batch 22 collective-delete
             * cascade wipe queued edits.
             */
            fun clearCachedFiles(context: Context) {
                listOf("attachments-pending", "attachments-view", "attachments").forEach { name ->
                    val dir = File(context.cacheDir, name)
                    if (dir.exists() && !dir.deleteRecursively()) {
                        Timber.w("Couldn't fully clear attachment cache dir %s", dir.absolutePath)
                    }
                }
            }

            /**
             * Internal cache file backing a staged upload (B-29). Worker
             * deletes this when the row reaches REMOTE / FAILED.
             */
            fun stagedFileFor(
                context: Context,
                attachmentId: String,
            ): File =
                File(
                    File(context.cacheDir, "attachments-pending"),
                    attachmentId.replace('/', '_'),
                )

            /** Generic type Nextcloud falls back to when it can't tell. */
            private const val OCTET_STREAM = "application/octet-stream"

            // S-13: filenames flow into markdown image refs `![…](…)` on the
            // share paths. The on-disk-illegal set is the base; the extra
            // markdown-meaningful punctuation prevents a hostile sharer
            // crafting a filename like `x)![pwn](https://…)` from injecting
            // markdown that server-side viewers would render.
            private val INVALID_FILENAME_CHARS = setOf(
                '/',
                '\\',
                ':',
                '*',
                '?',
                '"',
                '<',
                '>',
                '|',
                '(',
                ')',
                '[',
                ']',
                '!',
                '`',
                '\n',
                '\r',
            )
        }
    }

private fun AttachmentEntity.toDomain(remoteUrl: String?): Attachment =
    Attachment(
        id = id,
        pageId = pageId,
        fileName = fileName,
        contentType = contentType,
        sizeBytes = size,
        lastModifiedMs = lastModifiedMs,
        status = when (status) {
            AttachmentEntity.STATUS_REMOTE -> Attachment.Status.REMOTE
            AttachmentEntity.STATUS_PENDING -> Attachment.Status.PENDING
            AttachmentEntity.STATUS_UPLOADING -> Attachment.Status.UPLOADING
            else -> Attachment.Status.FAILED
        },
        remoteUrl = remoteUrl,
        localUriString = localUriString,
    )

/**
 * The [counter]th candidate name for [sanitised]: the name itself at 0, then
 * `stem-1.ext`, `stem-2.ext`, and so on.
 *
 * The suffix goes before the extension, not after it, so a renamed file
 * keeps the extension the content type and the image-vs-file decision are
 * read from. Only the *last* dot separates the extension, so `photo.tar.gz`
 * becomes `photo.tar-1.gz` — imperfect for double extensions, and the same
 * answer every other part of the app gets from `substringAfterLast('.')`.
 */
internal fun collisionCandidate(
    sanitised: String,
    counter: Int,
): String {
    if (counter == 0) return boundedFileName(sanitised)
    val stem = sanitised.substringBeforeLast('.', sanitised)
    val ext = sanitised.substringAfterLast('.', "")
    return if (ext.isEmpty()) fitFileName(stem, "-$counter") else fitFileName(stem, "-$counter.$ext")
}

/**
 * [name] truncated to [MAX_ATTACHMENT_NAME_BYTES] UTF-8 bytes, extension kept.
 *
 * Issue #43: the cap used to be `take(200)`, which counts UTF-16 `Char`s.
 * A name of 100 emoji is 200 of those and about 400 UTF-8 bytes, so it sailed
 * past a limit expressed in bytes — and `take` can land between the halves of
 * a surrogate pair, producing a name whose encoding is invalid before it even
 * reaches the length check. Both mean an upload that fails identically on
 * every attempt, with a retry that cannot help because the name never changes.
 *
 * B-32 had already fixed the same class of bug for page *titles* in
 * `sanitiseTitleForFilename`; the attachment sanitiser never got it. This
 * walks forward by code point rather than backward from a byte offset, which
 * additionally leaves room for the extension and the `-N` collision suffix.
 */
internal fun boundedFileName(name: String): String {
    val ext = name.substringAfterLast('.', "")
    return if (ext.isEmpty()) fitFileName(name, "") else fitFileName(name.substringBeforeLast('.'), ".$ext")
}

/**
 * `[stem] + [tail]`, truncated on code-point boundaries to fit
 * [MAX_ATTACHMENT_NAME_BYTES] UTF-8 bytes.
 *
 * [tail] is the part that carries meaning the rest of the app reads back —
 * the extension decides the content type and the image-vs-file demotion, the
 * `-N` says which copy this is — so the stem is what gives up room. An
 * absurdly long tail is itself truncated, always leaving at least one byte
 * for the stem, and a stem that truncates away entirely falls back to
 * [FALLBACK_STEM] rather than yielding a leading-dot name S-5 refuses.
 */
private fun fitFileName(
    stem: String,
    tail: String,
): String {
    val fittedTail = takeUtf8Bytes(tail, MAX_ATTACHMENT_NAME_BYTES - 1)
    val stemBudget = MAX_ATTACHMENT_NAME_BYTES - fittedTail.utf8ByteLength()
    val fittedStem = takeUtf8Bytes(stem, stemBudget)
    return if (fittedStem.isEmpty()) {
        takeUtf8Bytes(FALLBACK_STEM, stemBudget) + fittedTail
    } else {
        fittedStem + fittedTail
    }
}

/**
 * The longest prefix of [text] that encodes to at most [maxBytes] UTF-8
 * bytes, cut only between code points — so a surrogate pair is either kept
 * whole or dropped whole.
 */
private fun takeUtf8Bytes(
    text: String,
    maxBytes: Int,
): String {
    if (maxBytes <= 0) return ""
    var used = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val cost = utf8Cost(codePoint)
        if (used + cost > maxBytes) break
        used += cost
        index += Character.charCount(codePoint)
    }
    return text.substring(0, index)
}

internal fun String.utf8ByteLength(): Int {
    var total = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        total += utf8Cost(codePoint)
        index += Character.charCount(codePoint)
    }
    return total
}

private fun utf8Cost(codePoint: Int): Int =
    when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

/**
 * Nextcloud refuses a filename over 250 UTF-8 bytes. 200 keeps headroom for
 * the `.attachments.<pageId>/` prefix the server prepends and for anything
 * that re-encodes the name on the way. Distinct from
 * `sanitiseTitleForFilename`'s 250, which budgets for `.md` instead.
 */
internal const val MAX_ATTACHMENT_NAME_BYTES = 200

/** Stands in for a name that sanitised or truncated away to nothing. */
private const val FALLBACK_STEM = "attachment"

/** How [AttachmentRepository.delete] has to reach a given attachment. */
internal enum class DeleteRoute {
    /**
     * Keep the row as a `DELETING` tombstone, so the worker holding it
     * doesn't record its upload, and removes the file only if the upload is
     * answered with success (issues #35, #56).
     */
    Tombstone,

    /** On the server and listed, so OCS-4 can delete it by its own id. */
    ServerById,
}

/**
 * Issue #35: which of the two shapes a delete takes, for a row the delete
 * could not simply drop.
 *
 * A waiting or failed row is dropped where it stands (issue #56), so a row
 * this device still owns gets here when a worker holds it: its PUT may be on
 * the wire, and it goes to a tombstone whatever its status was when read.
 *
 * A row that is absent locally is treated as the server's: `delete` refreshes
 * to pick up its id, and reports if the server has never heard of it either.
 */
internal fun deleteRoute(existing: AttachmentEntity?): DeleteRoute =
    when {
        existing == null -> DeleteRoute.ServerById
        existing.status == AttachmentEntity.STATUS_REMOTE -> DeleteRoute.ServerById
        else -> DeleteRoute.Tombstone
    }
