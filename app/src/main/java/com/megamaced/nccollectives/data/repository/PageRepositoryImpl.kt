package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageRepositoryImpl
    @Inject
    constructor(
        private val api: CollectivesApiService,
        private val bodyService: PageBodyService,
        private val pageDao: PageDao,
        private val editQueueDao: EditQueueDao,
        private val syncScheduler: SyncScheduler,
    ) : PageRepository {
        override fun observePages(collectiveId: Long): Flow<List<Page>> =
            pageDao.observeForCollective(collectiveId).map { rows -> rows.map { it.toDomain() } }

        override fun observePage(pageId: Long): Flow<Page?> = pageDao.observeById(pageId).map { it?.toDomain() }

        override suspend fun refresh(collectiveId: Long): ApiResult<Unit> =
            apiCall {
                val now = System.currentTimeMillis()
                val response = api.listPages(collectiveId)
                val entities = response.ocs.data.pages.map { dto ->
                    val existing = pageDao.getById(dto.id)
                    dto.toEntity(
                        collectiveId = collectiveId,
                        now = now,
                        existingBody = existing?.bodyMd,
                        existingEtag = existing?.bodyEtag,
                        existingDraft = existing?.draftBodyMd,
                    )
                }
                pageDao.upsertAll(entities)
                pageDao.deleteMissingForCollective(collectiveId, entities.map { it.id })
            }

        override suspend fun getPage(pageId: Long): Page? = pageDao.getById(pageId)?.toDomain()

        override suspend fun fetchBody(pageId: Long): ApiResult<String> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val result = bodyService.fetchBody(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = entity.fileName,
            )
            return when (result) {
                is ApiResult.Success -> {
                    pageDao.updateBody(pageId, result.data.markdown, result.data.etag, System.currentTimeMillis())
                    ApiResult.Success(result.data.markdown)
                }
                is ApiResult.NetworkError -> result
                is ApiResult.HttpError -> result
                ApiResult.Unauthorised -> ApiResult.Unauthorised
                ApiResult.Conflict -> ApiResult.Conflict
                is ApiResult.Unexpected -> result
            }
        }

        override suspend fun saveBody(
            pageId: Long,
            newBody: String,
        ): SaveOutcome {
            val entity = pageDao.getById(pageId)
                ?: return SaveOutcome.Error("Page not cached")
            val result = bodyService.saveBody(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = entity.fileName,
                body = newBody,
                baseEtag = entity.bodyEtag,
            )
            return when (result) {
                is ApiResult.Success -> {
                    pageDao.updateBody(pageId, newBody, result.data, System.currentTimeMillis())
                    pageDao.updateDraft(pageId, null)
                    editQueueDao.deleteForPage(pageId)
                    SaveOutcome.Saved
                }
                is ApiResult.NetworkError -> {
                    editQueueDao.upsert(
                        EditQueueEntity(
                            pageId = pageId,
                            baseEtag = entity.bodyEtag,
                            newBodyMd = newBody,
                            queuedAt = System.currentTimeMillis(),
                            status = "PENDING",
                        ),
                    )
                    syncScheduler.flushEditsWhenOnline()
                    SaveOutcome.Queued
                }
                ApiResult.Conflict -> {
                    pageDao.updateDraft(pageId, newBody)
                    SaveOutcome.Conflict
                }
                ApiResult.Unauthorised -> SaveOutcome.Error(result.userMessage() ?: "Unauthorised")
                is ApiResult.HttpError -> SaveOutcome.Error(result.userMessage() ?: "Server error")
                is ApiResult.Unexpected -> SaveOutcome.Error(result.userMessage() ?: "Unexpected error")
            }
        }

        override suspend fun replaceWithDraft(
            pageId: Long,
            newBody: String,
        ): SaveOutcome {
            val entity = pageDao.getById(pageId)
                ?: return SaveOutcome.Error("Page not cached")
            // Force the write through by skipping the If-Match precondition.
            val result = bodyService.saveBody(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = entity.fileName,
                body = newBody,
                baseEtag = null,
            )
            return when (result) {
                is ApiResult.Success -> {
                    pageDao.updateBody(pageId, newBody, result.data, System.currentTimeMillis())
                    pageDao.updateDraft(pageId, null)
                    editQueueDao.deleteForPage(pageId)
                    SaveOutcome.Saved
                }
                is ApiResult.NetworkError -> SaveOutcome.Queued
                ApiResult.Conflict -> SaveOutcome.Conflict
                ApiResult.Unauthorised -> SaveOutcome.Error(result.userMessage() ?: "Unauthorised")
                is ApiResult.HttpError -> SaveOutcome.Error(result.userMessage() ?: "Server error")
                is ApiResult.Unexpected -> SaveOutcome.Error(result.userMessage() ?: "Unexpected error")
            }
        }

        override suspend fun discardDraft(pageId: Long) {
            pageDao.updateDraft(pageId, null)
        }

        override suspend fun setEmoji(
            pageId: Long,
            emoji: String,
        ): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val previous = entity.emoji
            // Optimistic local update.
            pageDao.updateEmoji(pageId, emoji.ifBlank { null })
            val result = apiCall { api.setPageEmoji(entity.collectiveId, pageId, emoji) }
            if (result !is ApiResult.Success) {
                pageDao.updateEmoji(pageId, previous)
            }
            return result
        }

        override suspend fun listTagsForCollective(collectiveId: Long): ApiResult<List<PageTag>> {
            val result = apiCall {
                api
                    .listTags(collectiveId)
                    .ocs.data.tags
            }
            return when (result) {
                is ApiResult.Success ->
                    ApiResult.Success(result.data.map { PageTag(id = it.id, name = it.name) })
                is ApiResult.NetworkError -> result
                is ApiResult.HttpError -> result
                ApiResult.Unauthorised -> ApiResult.Unauthorised
                ApiResult.Conflict -> ApiResult.Conflict
                is ApiResult.Unexpected -> result
            }
        }

        override suspend fun togglePageTag(
            pageId: Long,
            tagId: Long,
            tagName: String,
            add: Boolean,
        ): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val current = splitTags(entity.tagsCsv)
            val next = if (add) {
                if (tagName in current) current else current + tagName
            } else {
                current - tagName
            }
            if (next == current) return ApiResult.Success(Unit)
            // Optimistic update.
            pageDao.updateTagsCsv(pageId, joinTags(next))
            val result = apiCall {
                if (add) {
                    api.addPageTag(entity.collectiveId, pageId, tagId)
                } else {
                    api.removePageTag(entity.collectiveId, pageId, tagId)
                }
            }
            if (result !is ApiResult.Success) {
                pageDao.updateTagsCsv(pageId, entity.tagsCsv)
            }
            return result
        }

        override suspend fun renamePage(
            pageId: Long,
            newTitle: String,
        ): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            if (entity.isFolderPage()) {
                return ApiResult.Unexpected(
                    UnsupportedOperationException("Renaming folder pages isn't supported yet"),
                )
            }
            val cleaned = try {
                sanitiseTitleForFilename(newTitle)
            } catch (e: IllegalArgumentException) {
                return ApiResult.Unexpected(e)
            }
            if (cleaned == entity.title) return ApiResult.Success(Unit)
            val newFileName = "$cleaned.md"
            val previousTitle = entity.title
            val previousFileName = entity.fileName
            // Optimistic local update.
            pageDao.updateTitleAndPath(pageId, cleaned, newFileName, entity.filePath)
            val result = bodyService.moveFile(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = previousFileName,
                destCollectivePath = entity.collectivePath,
                destFilePath = entity.filePath,
                destFileName = newFileName,
            )
            if (result !is ApiResult.Success) {
                pageDao.updateTitleAndPath(pageId, previousTitle, previousFileName, entity.filePath)
            }
            return result
        }

        override suspend fun movePage(
            pageId: Long,
            newParentPageId: Long,
        ): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            if (entity.isFolderPage()) {
                return ApiResult.Unexpected(
                    UnsupportedOperationException("Moving folder pages isn't supported yet"),
                )
            }
            val newParent = pageDao.getById(newParentPageId) ?: return ApiResult.Unexpected(
                IllegalStateException("Target parent $newParentPageId not cached"),
            )
            if (newParent.collectiveId != entity.collectiveId) {
                return ApiResult.Unexpected(
                    UnsupportedOperationException("Cross-collective moves aren't supported yet"),
                )
            }
            // Children of a folder page live in that folder's filePath (the
            // directory containing its Readme.md). Children of the landing
            // page live at filePath "" — the collective root.
            val newFilePath = when {
                newParent.parentId == 0L -> "" // landing page → collective root
                newParent.isFolderPage() -> newParent.filePath
                else -> return ApiResult.Unexpected(
                    UnsupportedOperationException("Target page isn't a folder"),
                )
            }
            if (newFilePath == entity.filePath) return ApiResult.Success(Unit)
            val previousParentId = entity.parentId
            val previousFilePath = entity.filePath
            // Optimistic.
            pageDao.updateParentAndPath(pageId, newParentPageId, newFilePath)
            val result = bodyService.moveFile(
                collectivePath = entity.collectivePath,
                filePath = previousFilePath,
                fileName = entity.fileName,
                destCollectivePath = entity.collectivePath,
                destFilePath = newFilePath,
                destFileName = entity.fileName,
            )
            if (result !is ApiResult.Success) {
                pageDao.updateParentAndPath(pageId, previousParentId, previousFilePath)
            }
            return result
        }
    }
