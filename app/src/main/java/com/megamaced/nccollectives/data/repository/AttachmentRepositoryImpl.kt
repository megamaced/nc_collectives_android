package com.megamaced.nccollectives.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
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

        override suspend fun refresh(pageId: Long): ApiResult<Unit> {
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
                    database.withTransaction {
                        val existing = attachmentDao
                            .listForPage(pageId)
                            .associateBy { it.id }
                        val remoteEntities = result.data.ocs.data.attachments.map { dto ->
                            val key = AttachmentEntity.key(pageId, dto.name)
                            val current = existing[key]
                            if (current != null &&
                                (
                                    current.status == AttachmentEntity.STATUS_PENDING ||
                                        current.status == AttachmentEntity.STATUS_UPLOADING
                                )
                            ) {
                                // B-36: preserve pending row, but catch up the
                                // server-id so a subsequent OCS-4 delete can
                                // target it correctly.
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
                                    // OCS doesn't return an ETag; attachments don't use
                                    // If-Match anywhere so null is fine.
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
                    ApiResult.Success(Unit)
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
        ): String? {
            val resolvedName = resolveCollisionFreeName(pageId, suggestedFileName)
            val resolvedType = contentType ?: guessMimeType(resolvedName)
            // B-29: copy the picked/shared bytes into our own cache before
            // returning. Photo-picker URIs aren't persistable, the sender
            // may revoke FLAG_GRANT_READ_URI_PERMISSION at any time, and
            // the OS may evict the photo-picker cache before the worker
            // runs — copying here is the only way to guarantee the worker
            // can still read the bytes on the other side of process death.
            val stagedFile = copyToStaging(pageId, resolvedName, sourceUri)
                ?: return null
            val entity = AttachmentEntity(
                id = AttachmentEntity.key(pageId, resolvedName),
                pageId = pageId,
                fileName = resolvedName,
                contentType = resolvedType,
                size = stagedFile.length(),
                lastModifiedMs = System.currentTimeMillis(),
                etag = null,
                status = AttachmentEntity.STATUS_PENDING,
                localUriString = Uri.fromFile(stagedFile).toString(),
                lastSyncedAt = System.currentTimeMillis(),
            )
            attachmentDao.upsert(entity)
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

        private suspend fun copyToStaging(
            pageId: Long,
            resolvedName: String,
            sourceUri: Uri,
        ): File? =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "attachments-pending").apply { mkdirs() }
                // Encode the file with the same key the DB row uses so the
                // worker can find/delete it without an extra Room read.
                val staged = File(dir, AttachmentEntity.key(pageId, resolvedName).replace('/', '_'))
                try {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    staged
                } catch (e: SecurityException) {
                    Timber.w(e, "Source URI %s not readable for staging", sourceUri)
                    staged.delete()
                    null
                } catch (e: java.io.IOException) {
                    Timber.w(e, "Failed to stage %s", sourceUri)
                    staged.delete()
                    null
                }
            }

        override suspend fun delete(
            pageId: Long,
            fileName: String,
        ): ApiResult<Unit> {
            val page = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val key = AttachmentEntity.key(pageId, fileName)
            val existing = attachmentDao.getById(key)
            if (existing != null && existing.status != AttachmentEntity.STATUS_REMOTE) {
                // Pending / failed uploads never made it to the server; just
                // drop the local row.
                attachmentDao.delete(key)
                // Issue #23: and the bytes behind it. Dropping only the row
                // orphaned the staging copy — invisible to the UI, still on
                // disk, and now that a `FAILED` row keeps its bytes on
                // purpose this is the path that has to collect them.
                deleteStagedFile(key)
                return ApiResult.Success(Unit)
            }
            // OCS-4: delete by server-assigned id (Batch 18j). Replaces the
            // previous WebDAV DELETE by user-typed filename. If the row
            // doesn't have a serverId cached yet (e.g. user uploaded but
            // hasn't refreshed the attachments screen since), refresh
            // inline to populate it before deleting.
            var serverId = existing?.serverAttachmentId
            if (serverId == null) {
                val refreshed = refresh(pageId)
                if (refreshed !is ApiResult.Success) return refreshed
                serverId = attachmentDao.getById(key)?.serverAttachmentId
                    ?: return ApiResult.Unexpected(
                        IllegalStateException("Attachment $fileName not found on server"),
                    )
            }
            val result = apiCall { api.deleteAttachment(page.collectiveId, pageId, serverId) }
            if (result is ApiResult.Success) {
                attachmentDao.delete(key)
            }
            return result
        }

        override suspend fun retryUpload(
            pageId: Long,
            fileName: String,
        ): ApiResult<Unit> {
            val key = AttachmentEntity.key(pageId, fileName)
            val existing = attachmentDao.getById(key)
                ?: return ApiResult.Unexpected(IllegalStateException("Attachment $fileName not found"))
            if (existing.status == AttachmentEntity.STATUS_REMOTE) return ApiResult.Success(Unit)
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
            attachmentDao.setStatus(key, AttachmentEntity.STATUS_PENDING)
            syncScheduler.flushAttachmentUploadsWhenOnline()
            return ApiResult.Success(Unit)
        }

        override suspend fun renameForRemoteCollision(
            pageId: Long,
            fileName: String,
        ): String? {
            val oldKey = AttachmentEntity.key(pageId, fileName)
            val row = attachmentDao.getById(oldKey) ?: return null
            // Best-effort: a fresh listing is what lets the local resolver
            // step past the names the server already holds, rather than
            // handing back one it will refuse for the same reason. Offline,
            // or on a failure, the resolver still bumps past our own row and
            // the next 412 brings us back here.
            refresh(pageId)
            val newName = resolveCollisionFreeName(pageId, fileName)
            val newKey = AttachmentEntity.key(pageId, newName)
            if (newKey == oldKey) return null
            // The staged bytes are keyed on the row id, so the file moves
            // with the row. Bail rather than re-queue if it can't: a PENDING
            // row with no bytes behind it just fails again.
            val oldStaged = stagedFileFor(context, oldKey)
            val newStaged = stagedFileFor(context, newKey)
            if (!oldStaged.exists() || !oldStaged.renameTo(newStaged)) {
                Timber.w("Couldn't move the staged copy of %s aside", oldKey)
                return null
            }
            database.withTransaction {
                attachmentDao.delete(oldKey)
                attachmentDao.upsert(
                    row.copy(
                        id = newKey,
                        fileName = newName,
                        status = AttachmentEntity.STATUS_PENDING,
                        localUriString = Uri.fromFile(newStaged).toString(),
                        // The old row's server id, if it had one, belonged to
                        // whatever is sitting at the old name.
                        serverAttachmentId = null,
                    ),
                )
            }
            Timber.i("Attachment name %s was taken on the server; re-queued as %s", fileName, newName)
            return newName
        }

        private fun deleteStagedFile(attachmentId: String) {
            val staged = stagedFileFor(context, attachmentId)
            if (staged.exists() && !staged.delete()) {
                Timber.w("Couldn't delete the staged copy at %s", staged.absolutePath)
            }
        }

        override suspend fun downloadForViewing(
            pageId: Long,
            relativePath: String,
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

            val target = viewCacheFileFor(context, pageId, fileName)
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
                .ifEmpty { "attachment" }
            // Cap at 200 chars — Nextcloud's hard cap is 250 bytes but UTF-8
            // headroom keeps us safe.
            return if (cleaned.length > 200) cleaned.take(200) else cleaned
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
             * currently reading through our FileProvider grant.
             */
            fun viewCacheFileFor(
                context: Context,
                pageId: Long,
                fileName: String,
            ): File =
                File(
                    File(File(context.cacheDir, "attachments-view"), pageId.toString()),
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
    if (counter == 0) return sanitised
    val stem = sanitised.substringBeforeLast('.', sanitised)
    val ext = sanitised.substringAfterLast('.', "")
    return if (ext.isEmpty()) "$stem-$counter" else "$stem-$counter.$ext"
}
