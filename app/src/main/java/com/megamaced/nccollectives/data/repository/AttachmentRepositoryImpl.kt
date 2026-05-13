package com.megamaced.nccollectives.data.repository

import android.net.Uri
import android.webkit.MimeTypeMap
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.domain.model.Attachment
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepositoryImpl
    @Inject
    constructor(
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
            val dir = attachmentsDirectoryFor(pageId)
            val result = bodyService.propfind(
                collectivePath = page.collectivePath,
                filePath = page.filePath,
                directoryName = dir,
            )
            return when (result) {
                is ApiResult.Success -> {
                    val now = System.currentTimeMillis()
                    val remoteEntities = result.data.map { entry ->
                        AttachmentEntity(
                            id = AttachmentEntity.key(pageId, entry.displayName),
                            pageId = pageId,
                            fileName = entry.displayName,
                            contentType = entry.contentType,
                            size = entry.size,
                            lastModifiedMs = entry.lastModifiedMs,
                            etag = entry.etag,
                            status = AttachmentEntity.STATUS_REMOTE,
                            localUriString = null,
                            lastSyncedAt = now,
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
        ): String {
            val resolvedName = resolveCollisionFreeName(pageId, suggestedFileName)
            val resolvedType = contentType ?: guessMimeType(resolvedName)
            val entity = AttachmentEntity(
                id = AttachmentEntity.key(pageId, resolvedName),
                pageId = pageId,
                fileName = resolvedName,
                contentType = resolvedType,
                size = 0,
                lastModifiedMs = System.currentTimeMillis(),
                etag = null,
                status = AttachmentEntity.STATUS_PENDING,
                localUriString = sourceUri.toString(),
                lastSyncedAt = System.currentTimeMillis(),
            )
            attachmentDao.upsert(entity)
            syncScheduler.flushAttachmentUploadsWhenOnline()
            return entity.id
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
            val dir = attachmentsDirectoryFor(pageId)
            val result = bodyService.deleteFile(
                collectivePath = page.collectivePath,
                filePath = combinePath(page.filePath, dir),
                fileName = fileName,
            )
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

            private val INVALID_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
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
