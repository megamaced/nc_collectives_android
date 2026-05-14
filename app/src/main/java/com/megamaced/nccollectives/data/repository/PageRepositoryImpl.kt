package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

        override suspend fun listTagsForCollective(collectiveId: Long): ApiResult<List<PageTag>> =
            apiCall {
                api
                    .listTags(collectiveId)
                    .ocs.data.tags
            }.mapSuccess { tags -> tags.map { PageTag(id = it.id, name = it.name) } }

        override suspend fun createTag(
            collectiveId: Long,
            name: String,
            color: String,
        ): ApiResult<PageTag> =
            apiCall {
                api
                    .createTag(collectiveId, name, color)
                    .ocs.data.tag
            }.mapSuccess { dto -> PageTag(id = dto.id, name = dto.name) }

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
            val cleaned = try {
                sanitiseTitleForFilename(newTitle)
            } catch (e: IllegalArgumentException) {
                return ApiResult.Unexpected(e)
            }
            if (cleaned == entity.title) return ApiResult.Success(Unit)
            // OCS-2: `PUT /pages/{id}` body `{title}` renames atomically,
            // including the directory in the folder-page case. Replaces
            // the previous WebDAV MOVE + manual Room repath, lifts the
            // folder-page refusal, and surfaces structured server errors
            // for the rename-collision case (B-20).
            val result = apiCall {
                api.updatePage(entity.collectiveId, pageId, mapOf("title" to cleaned))
            }
            if (result is ApiResult.Success) {
                // Refresh the collective to pick up any cascading filePath
                // changes on descendants (folder rename moves the whole
                // directory) and to reconcile if the server changed the
                // page's id during the move (gotcha #16).
                refresh(entity.collectiveId)
            }
            return result.mapSuccess { }
        }

        override suspend fun createPage(
            collectiveId: Long,
            parentPageId: Long,
            title: String,
            body: String,
        ): ApiResult<Page> {
            val parent = pageDao.getById(parentPageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Parent $parentPageId not cached"))
            if (parent.collectiveId != collectiveId) {
                return ApiResult.Unexpected(IllegalStateException("Parent belongs to a different collective"))
            }
            val cleaned = try {
                sanitiseTitleForFilename(title)
            } catch (e: IllegalArgumentException) {
                return ApiResult.Unexpected(e)
            }
            // OCS-1: `POST /pages/{parentId}` handles indexing, naming, and
            // folder promotion atomically on the server. Replaces the
            // previous WebDAV PUT + refresh-poll dance — which raced under
            // a cold cache (B-3) and refused leaf parents because we didn't
            // know how to promote them. The server promotes a leaf parent
            // to a folder transparently, so the previous `isFolderPage()`
            // guard is gone.
            val createResult = apiCall {
                api.createPage(collectiveId, parentPageId, cleaned)
            }
            val createdDto = when (createResult) {
                is ApiResult.Success -> createResult.data.ocs.data.page
                is ApiResult.NetworkError -> return createResult
                is ApiResult.HttpError -> return createResult
                ApiResult.Unauthorised -> return ApiResult.Unauthorised
                ApiResult.Conflict -> return ApiResult.Conflict
                is ApiResult.Unexpected -> return createResult
            }
            // Persist the created page locally. Refresh the collective so
            // any side-effect of folder promotion (parent's `filePath` may
            // change, parent's `subpageOrder` updates) lands too.
            val now = System.currentTimeMillis()
            pageDao.upsertAll(
                listOf(
                    createdDto.toEntity(
                        collectiveId = collectiveId,
                        now = now,
                        existingBody = null,
                        existingEtag = null,
                        existingDraft = null,
                    ),
                ),
            )
            refresh(collectiveId)
            // If the caller supplied an initial body, WebDAV PUT it to the
            // new page's path. OCS POST creates an empty page; body content
            // is set via the file in the user's Files area. Failure here
            // doesn't unwind the create — the page exists and the user can
            // edit it. We just surface the failure so they know the body
            // didn't land.
            if (body.isNotEmpty()) {
                val bodyResult = bodyService.uploadFile(
                    collectivePath = createdDto.collectivePath,
                    filePath = createdDto.filePath,
                    fileName = createdDto.fileName,
                    body = body.toRequestBody("text/markdown; charset=utf-8".toMediaType()),
                )
                when (bodyResult) {
                    is ApiResult.Success -> Unit
                    is ApiResult.NetworkError -> return bodyResult
                    is ApiResult.HttpError -> return bodyResult
                    ApiResult.Unauthorised -> return ApiResult.Unauthorised
                    ApiResult.Conflict -> return ApiResult.Conflict
                    is ApiResult.Unexpected -> return bodyResult
                }
            }
            val saved = pageDao.getById(createdDto.id)
                ?: return ApiResult.Unexpected(
                    IllegalStateException("Page ${createdDto.id} disappeared from cache after create"),
                )
            return ApiResult.Success(saved.toDomain())
        }

        override suspend fun trashPage(pageId: Long): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            if (entity.parentId == 0L) {
                return ApiResult.Unexpected(
                    UnsupportedOperationException("Can't trash the landing page — delete the collective instead"),
                )
            }
            val result = apiCall { api.trashPage(entity.collectiveId, pageId) }
            if (result is ApiResult.Success) {
                // Drop the local row directly. The previous keep-list dance
                // (observe → first → filter → deleteMissingForCollective)
                // raced against parallel syncs and could drop unrelated rows
                // — see B-9 in the audit findings.
                pageDao.deleteById(pageId)
            }
            return result
        }

        override suspend fun listTrashedPages(collectiveId: Long): ApiResult<List<Page>> =
            apiCall { api.listTrashedPages(collectiveId) }.mapSuccess { envelope ->
                val now = System.currentTimeMillis()
                envelope.ocs.data.pages.map { dto ->
                    dto
                        .toEntity(
                            collectiveId = collectiveId,
                            now = now,
                            existingBody = null,
                            existingEtag = null,
                            existingDraft = null,
                        ).toDomain()
                }
            }

        override suspend fun restorePage(
            collectiveId: Long,
            pageId: Long,
        ): ApiResult<Unit> {
            val result = apiCall { api.restoreTrashedPage(collectiveId, pageId) }
            if (result is ApiResult.Success) {
                refresh(collectiveId)
            }
            return result
        }

        override suspend fun purgePage(
            collectiveId: Long,
            pageId: Long,
        ): ApiResult<Unit> = apiCall { api.purgeTrashedPage(collectiveId, pageId) }

        override fun observeBacklinksFor(
            collectiveId: Long,
            pageId: Long,
        ): Flow<List<Page>> =
            pageDao.observeForCollective(collectiveId).map { rows ->
                rows
                    .asSequence()
                    .filter { row -> row.id != pageId && pageId in row.linkedPageIdsCsv.toLongCsvList() }
                    .map { it.toDomain() }
                    .toList()
            }

        override suspend fun resolvePageByTitle(
            collectiveId: Long,
            title: String,
        ): Long? {
            val cleaned = title
                .trim()
                .removeSuffix(".md")
                .removeSuffix(".MD")
                .trim()
            if (cleaned.isEmpty()) return null
            return pageDao.findIdByTitleInCollective(collectiveId, cleaned)
        }

        override suspend fun appendToPage(
            pageId: Long,
            text: String,
        ): SaveOutcome {
            val entity = pageDao.getById(pageId)
                ?: return SaveOutcome.Error("Page not cached")
            // Make sure we have the current body before appending, otherwise
            // we'd overwrite the page with just the appended snippet.
            val baseBody = if (entity.bodyMd == null) {
                val fetched = fetchBody(pageId)
                if (fetched !is ApiResult.Success) {
                    return SaveOutcome.Error(fetched.userMessage() ?: "Couldn't load page body")
                }
                fetched.data
            } else {
                entity.bodyMd
            }
            val separator = if (baseBody.isEmpty() || baseBody.endsWith('\n')) "" else "\n"
            val newBody = baseBody + separator + text
            return saveBody(pageId, newBody)
        }

        override suspend fun movePage(
            pageId: Long,
            newParentPageId: Long,
        ): ApiResult<Unit> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val newParent = pageDao.getById(newParentPageId) ?: return ApiResult.Unexpected(
                IllegalStateException("Target parent $newParentPageId not cached"),
            )
            if (newParent.collectiveId != entity.collectiveId) {
                // Cross-collective moves use a separate `PUT /pages/{id}/to/{newCollectiveId}`
                // endpoint. Out of scope for now.
                return ApiResult.Unexpected(
                    UnsupportedOperationException("Cross-collective moves aren't supported"),
                )
            }
            if (entity.parentId == newParentPageId) return ApiResult.Success(Unit)
            // OCS-2: `PUT /pages/{id}` body `{parentId}` moves the page
            // (and its directory, if it's a folder page) atomically.
            // Server handles leaf-to-folder promotion of the new parent,
            // so the previous `isFolderPage()` guard on the target is
            // gone — same as createPage in 18h.
            val result = apiCall {
                api.updatePage(entity.collectiveId, pageId, mapOf("parentId" to newParentPageId.toString()))
            }
            if (result is ApiResult.Success) {
                refresh(entity.collectiveId)
            }
            return result.mapSuccess { }
        }
    }
