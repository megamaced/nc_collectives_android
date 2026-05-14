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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
            if (!parent.isFolderPage()) {
                return ApiResult.Unexpected(
                    UnsupportedOperationException(
                        "Pick a folder page or the landing page — new pages can only nest under a folder",
                    ),
                )
            }
            val cleaned = try {
                sanitiseTitleForFilename(title)
            } catch (e: IllegalArgumentException) {
                return ApiResult.Unexpected(e)
            }
            val newFileName = "$cleaned.md"
            // Children of a folder page live in that folder's filePath. For
            // the landing page (parentId == 0) that's the collective root,
            // i.e. filePath == "".
            val childFilePath = parent.filePath
            // Refuse to silently overwrite an existing sibling — WebDAV PUT
            // doesn't carry an If-None-Match in our wrapper, and a clash is
            // almost always user error rather than intent.
            val existingSibling = pageDao.observeForCollective(collectiveId).first().firstOrNull {
                it.parentId == parentPageId && it.title.equals(cleaned, ignoreCase = true)
            }
            if (existingSibling != null) {
                return ApiResult.Unexpected(
                    IllegalStateException("A page titled \"$cleaned\" already exists under this parent"),
                )
            }
            val putResult = bodyService.uploadFile(
                collectivePath = parent.collectivePath,
                filePath = childFilePath,
                fileName = newFileName,
                body = body.toRequestBody("text/markdown; charset=utf-8".toMediaType()),
            )
            when (putResult) {
                is ApiResult.Success -> Unit
                is ApiResult.NetworkError -> return putResult
                is ApiResult.HttpError -> return putResult
                ApiResult.Unauthorised -> return ApiResult.Unauthorised
                ApiResult.Conflict -> return ApiResult.Conflict
                is ApiResult.Unexpected -> return putResult
            }
            // Collectives indexes the new file via its filesystem watcher;
            // the page list usually contains it after a short pause. Try
            // immediately, then once more with a brief delay.
            val refreshed = refresh(collectiveId)
            when (refreshed) {
                is ApiResult.Success -> Unit
                is ApiResult.NetworkError -> return refreshed
                is ApiResult.HttpError -> return refreshed
                ApiResult.Unauthorised -> return ApiResult.Unauthorised
                ApiResult.Conflict -> return ApiResult.Conflict
                is ApiResult.Unexpected -> return refreshed
            }
            var created = findCreatedPage(collectiveId, parentPageId, cleaned)
            if (created == null) {
                delay(750)
                refresh(collectiveId)
                created = findCreatedPage(collectiveId, parentPageId, cleaned)
            }
            return created?.let { ApiResult.Success(it) }
                ?: ApiResult.Unexpected(
                    IllegalStateException("Page created but server hasn't indexed it yet"),
                )
        }

        private suspend fun findCreatedPage(
            collectiveId: Long,
            parentPageId: Long,
            title: String,
        ): Page? {
            val rows = pageDao.observeForCollective(collectiveId).first()
            return rows.firstOrNull { it.parentId == parentPageId && it.title == title }?.toDomain()
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
