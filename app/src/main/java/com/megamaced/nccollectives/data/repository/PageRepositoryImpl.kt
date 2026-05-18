package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.TAG_SEP_STRING
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.data.toLongCsv
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
        private val database: NcCollectivesDatabase,
    ) : PageRepository {
        override fun observePages(collectiveId: Long): Flow<List<Page>> =
            pageDao.observeForCollective(collectiveId).map { rows -> rows.map { it.toDomain() } }

        override fun observeRecentPages(
            collectiveId: Long,
            limit: Int,
        ): Flow<List<Page>> =
            pageDao
                .observeRecentInCollective(collectiveId, limit)
                .map { rows -> rows.map { it.toDomain() } }

        override fun observePage(pageId: Long): Flow<Page?> = pageDao.observeById(pageId).map { it?.toDomain() }

        override suspend fun refresh(collectiveId: Long): ApiResult<Unit> =
            apiCall {
                val now = System.currentTimeMillis()
                val tagNames = fetchTagNamesById(collectiveId)
                val response = api.listPages(collectiveId)
                val entities = response.ocs.data.pages.map { dto ->
                    val existing = pageDao.getById(dto.id)
                    dto.toEntity(
                        collectiveId = collectiveId,
                        now = now,
                        existingBody = existing?.bodyMd,
                        existingEtag = existing?.bodyEtag,
                        existingDraft = existing?.draftBodyMd,
                        tagNamesById = tagNames,
                    )
                }
                // B-43: upsert + reconcile in one transaction. A parallel
                // refresh (e.g. SyncWorker overlapping the foreground caller)
                // can otherwise observe the intermediate "upserted but not
                // yet reconciled" state, causing flicker or — worse — wipe
                // rows the parallel run just inserted. B-42: avoid the
                // `WHERE id NOT IN ()` SQL syntax error by short-circuiting
                // on an empty keep-list to `deleteForCollective`.
                database.withTransaction {
                    pageDao.upsertAll(entities)
                    val keepIds = entities.map { it.id }
                    if (keepIds.isEmpty()) {
                        pageDao.deleteForCollective(collectiveId)
                    } else {
                        pageDao.deleteMissingForCollective(collectiveId, keepIds)
                    }
                }
            }

        /**
         * Server returns `PageDto.tags` as numeric IDs; we resolve them to
         * names at mapping time by pulling the per-collective tag list. A
         * failure here returns an empty map so a tag-service blip doesn't
         * break the page list — affected pages will display with no tag chips
         * until the next refresh.
         */
        private suspend fun fetchTagNamesById(collectiveId: Long): Map<Long, String> {
            val result = apiCall {
                api
                    .listTags(collectiveId)
                    .ocs.data.tags
            }
            return if (result is ApiResult.Success) {
                result.data.associate { it.id to it.name }
            } else {
                emptyMap()
            }
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
                    // If a prior save lost an etag race and is still
                    // CONFLICTED, refuse to queue a fresh edit on top — the
                    // `@Upsert` would clobber the conflict marker (B-19) and
                    // the user would silently lose the original draft. The
                    // existing draft is on the page row; the user resolves
                    // it via the `ConflictBanner` before queueing more.
                    val existing = editQueueDao.forPage(pageId)
                    if (existing?.status == "CONFLICTED") {
                        SaveOutcome.Conflict
                    } else {
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
                is ApiResult.NetworkError -> {
                    // B-38: the previous "return Queued without queuing"
                    // path left the draft sitting on the page row indefinitely.
                    // Mirror saveBody's offline branch but mark the entry as
                    // a force-write so the flush worker doesn't second-guess
                    // the user's explicit "Replace with my draft" intent on
                    // a 412 (B-46).
                    editQueueDao.upsert(
                        EditQueueEntity(
                            pageId = pageId,
                            baseEtag = null,
                            newBodyMd = newBody,
                            queuedAt = System.currentTimeMillis(),
                            status = "PENDING",
                            forceWrite = true,
                        ),
                    )
                    syncScheduler.flushEditsWhenOnline()
                    SaveOutcome.Queued
                }
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

        override suspend fun listTrashedPages(collectiveId: Long): ApiResult<List<Page>> {
            val tagNames = fetchTagNamesById(collectiveId)
            return apiCall { api.listTrashedPages(collectiveId) }.mapSuccess { envelope ->
                val now = System.currentTimeMillis()
                envelope.ocs.data.pages.map { dto ->
                    dto
                        .toEntity(
                            collectiveId = collectiveId,
                            now = now,
                            existingBody = null,
                            existingEtag = null,
                            existingDraft = null,
                            tagNamesById = tagNames,
                        ).toDomain()
                }
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

        override fun observePagesWithTagInCollective(
            collectiveId: Long,
            tagName: String,
        ): Flow<List<Page>> {
            // SQLite LIKE: `%` / `_` in a tag name would behave as wildcards.
            // Rare in practice, but the post-fetch split+exact-match filter
            // below absorbs both false positives and the (unlikely) case
            // where a tag was reordered into a different CSV slot between
            // the LIKE eval and the row read.
            val likePattern = "%$TAG_SEP_STRING$tagName$TAG_SEP_STRING%"
            return pageDao
                .observePagesWithTagInCollective(collectiveId, TAG_SEP_STRING, likePattern)
                .map { rows ->
                    rows
                        .filter { tagName in splitTags(it.tagsCsv) }
                        .map { it.toDomain() }
                }
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
            // Two newlines, not one — otherwise an append to a body ending
            // in `# Heading` produces `# Heading\nshared text` which parses
            // *inside* the heading (B-16). The blank line forces a fresh
            // markdown block.
            val newBody = when {
                baseBody.isEmpty() -> text
                baseBody.endsWith("\n\n") -> baseBody + text
                baseBody.endsWith("\n") -> baseBody + "\n" + text
                else -> baseBody + "\n\n" + text
            }
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

        override suspend fun copyPage(
            collectiveId: Long,
            pageId: Long,
        ): ApiResult<Page> {
            val now = System.currentTimeMillis()
            val result = apiCall { api.copyPage(collectiveId, pageId, copy = true) }
            return result.mapSuccess { envelope ->
                val createdDto = envelope.ocs.data.page
                val entity = createdDto.toEntity(
                    collectiveId = collectiveId,
                    now = now,
                    existingBody = null,
                    existingEtag = null,
                    existingDraft = null,
                )
                pageDao.upsertAll(listOf(entity))
                // Refresh the collective so the parent's `subpageOrder` and
                // any other side-effects of duplication (folder promotion if
                // the source was a folder) land too.
                refresh(collectiveId)
                entity.toDomain()
            }
        }

        override suspend fun setSubpageOrder(
            collectiveId: Long,
            parentPageId: Long,
            subpageOrderIds: List<Long>,
        ): ApiResult<Unit> {
            val parent = pageDao.getById(parentPageId)
                ?: return ApiResult.Unexpected(
                    IllegalStateException("Parent page $parentPageId not cached"),
                )
            val previousCsv = parent.subpageOrderCsv
            val nextCsv = subpageOrderIds.toLongCsv()
            if (nextCsv == previousCsv) return ApiResult.Success(Unit)

            // Optimistic local write — the tree's order is driven by the
            // parent's `subpageOrderCsv` (Batch 23), so the row reshuffles
            // before the network call returns.
            pageDao.updateSubpageOrderCsv(parentPageId, nextCsv)
            val subpageOrderJson = subpageOrderIds.joinToString(prefix = "[", postfix = "]", separator = ",")
            val result = apiCall {
                api.setSubpageOrder(collectiveId, parentPageId, subpageOrderJson)
            }
            if (result !is ApiResult.Success) {
                pageDao.updateSubpageOrderCsv(parentPageId, previousCsv)
            }
            return result.mapSuccess { }
        }
    }
