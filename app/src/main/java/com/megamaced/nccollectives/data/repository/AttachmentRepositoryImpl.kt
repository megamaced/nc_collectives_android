package com.megamaced.nccollectives.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.domain.model.Attachment
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
    ) : AttachmentRepository {
        override fun observeForPage(pageId: Long): Flow<List<Attachment>> =
            attachmentDao.observeForPage(pageId).map { rows ->
                rows.map { entity -> entity.toDomain(remoteUrlFor(entity)) }
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
                    val remoteEntities = result.data.ocs.data.attachments.map { dto ->
                        AttachmentEntity(
                            id = AttachmentEntity.key(pageId, dto.name),
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
                    attachmentDao.upsertAll(remoteEntities)
                    attachmentDao.deleteMissingRemoteForPage(pageId, remoteEntities.map { it.id })
                    ApiResult.Success(Unit)
                }
                else -> mapNonSuccess(result)
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
            return resolvedName
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

        override suspend fun urlFor(
            pageId: Long,
            fileName: String,
        ): String? {
            val page = pageDao.getById(pageId) ?: return null
            val dir = attachmentsDirectoryFor(pageId)
            return bodyService.resourceUrl(
                collectivePath = page.collectivePath,
                filePath = combinePath(page.filePath, dir),
                fileName = fileName,
            )
        }

        override suspend fun attachmentsBaseUrl(pageId: Long): String? {
            val page = pageDao.getById(pageId) ?: return null
            val dir = attachmentsDirectoryFor(pageId)
            val withDummy = bodyService.resourceUrl(
                collectivePath = page.collectivePath,
                filePath = combinePath(page.filePath, dir),
                fileName = "_",
            )
            return withDummy.removeSuffix("_")
        }

        private suspend fun remoteUrlFor(entity: AttachmentEntity): String? {
            if (entity.status != AttachmentEntity.STATUS_REMOTE) return null
            val page = pageDao.getById(entity.pageId) ?: return null
            return try {
                val dir = attachmentsDirectoryFor(entity.pageId)
                bodyService.resourceUrl(
                    collectivePath = page.collectivePath,
                    filePath = combinePath(page.filePath, dir),
                    fileName = entity.fileName,
                )
            } catch (_: Exception) {
                null
            }
        }

        private suspend fun resolveCollisionFreeName(
            pageId: Long,
            suggested: String,
        ): String {
            val sanitised = sanitiseFileName(suggested)
            val stem = sanitised.substringBeforeLast('.', sanitised)
            val ext = sanitised.substringAfterLast('.', "")
            var candidate = sanitised
            var counter = 1
            while (attachmentDao.getById(AttachmentEntity.key(pageId, candidate)) != null) {
                candidate = if (ext.isEmpty()) "$stem-$counter" else "$stem-$counter.$ext"
                counter++
            }
            return candidate
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

        private fun mapNonSuccess(result: ApiResult<*>): ApiResult<Unit> =
            when (result) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.NetworkError -> result
                is ApiResult.HttpError -> result
                ApiResult.Unauthorised -> ApiResult.Unauthorised
                ApiResult.Conflict -> ApiResult.Conflict
                is ApiResult.Unexpected -> result
            }

        companion object {
            /** Nextcloud Collectives stores per-page attachments here. */
            fun attachmentsDirectoryFor(pageId: Long): String = ".attachments.$pageId"

            fun combinePath(
                base: String,
                child: String,
            ): String = if (base.isEmpty()) child else "$base/$child"

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
