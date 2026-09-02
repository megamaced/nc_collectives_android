package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.ServerStringValidation
import com.megamaced.nccollectives.data.TAG_SEP_STRING
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.ConditionalBody
import com.megamaced.nccollectives.data.api.PageBodyService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.dto.PageDto
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.db.entity.EditQueueEntity
import com.megamaced.nccollectives.data.db.entity.PageEntity
import com.megamaced.nccollectives.data.joinTags
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.splitTags
import com.megamaced.nccollectives.data.toJsonLongArray
import com.megamaced.nccollectives.data.toLongCsv
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageCreation
import com.megamaced.nccollectives.domain.model.PageListItem
import com.megamaced.nccollectives.domain.model.PageTag
import com.megamaced.nccollectives.domain.model.SaveOutcome
import com.megamaced.nccollectives.domain.repository.AttachmentRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.sync.SyncScheduler
import com.megamaced.nccollectives.util.retargetAttachmentRefs
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber
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
        private val attachmentDao: AttachmentDao,
        private val syncScheduler: SyncScheduler,
        private val database: NcCollectivesDatabase,
        private val accountGeneration: AccountGeneration,
        // Issue #39: a rename or move can reissue the page's file id, and the
        // attachment rows keyed on the old one have to travel with it —
        // including the staged bytes, which only this repository knows how to
        // move. `dagger.Lazy` because it is used on one branch of two methods
        // and the graph should not pay to build it for every page read.
        private val attachmentRepository: Lazy<AttachmentRepository>,
    ) : PageRepository {
        override fun observePageList(collectiveId: Long): Flow<List<PageListItem>> =
            pageDao
                .observeForCollective(collectiveId)
                // R-55: Room invalidates per *table*, so every `updateBody`
                // — one per page open, via the B-58 revalidation — re-runs
                // this query and re-emits the whole collective. None of
                // those emissions differ in a single list-visible field.
                // Filtering on the projection rather than after the mapper
                // also skips re-mapping every row.
                .distinctUntilChanged()
                .map { rows -> rows.map { it.toDomain() } }

        override fun observePages(collectiveId: Long): Flow<List<Page>> =
            pageDao
                .observeDetailForCollective(collectiveId)
                // Deliberately *not* distinct-until-changed: these rows
                // carry bodies, so holding the previous emission to compare
                // against would pin a second copy of every cached body in
                // the collective. R-55's filter belongs on the projection.
                .map { rows -> rows.map { it.toDomain() } }

        override fun observeRecentPages(
            collectiveId: Long,
            limit: Int,
        ): Flow<List<PageListItem>> =
            pageDao
                .observeRecentInCollective(collectiveId, limit)
                .distinctUntilChanged()
                .map { rows -> rows.map { it.toDomain() } }

        /**
         * Issue #18: a queued offline edit is the local truth for the body,
         * so the page row's cached server markdown is overlaid with it. The
         * two flows are combined rather than joined in SQL so `pages` keeps
         * its plain `SELECT *` and the ETag on the row keeps meaning "the
         * version the server has".
         */
        override fun observePage(pageId: Long): Flow<Page?> =
            combine(
                pageDao.observeById(pageId),
                editQueueDao.observePendingBody(pageId),
            ) { entity, pendingBody -> entity?.toDomain(pendingBody) }

        /**
         * The landing card renders a snippet of this body, so it needs the
         * same overlay as [observePage] — otherwise a collective whose
         * landing page was edited offline advertises the pre-edit text.
         *
         * `flatMapLatest` because the page id isn't known until the row
         * arrives. The re-subscription it costs per emission is one indexed
         * single-row lookup on a table with at most one row per unsynced
         * page, against an upstream that already re-reads a whole markdown
         * body each time.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun observeLandingPage(collectiveId: Long): Flow<Page?> =
            pageDao
                .observeLandingPage(collectiveId)
                .flatMapLatest { entity ->
                    if (entity == null) {
                        flowOf(null)
                    } else {
                        editQueueDao.observePendingBody(entity.id).map { entity.toDomain(it) }
                    }
                }

        override suspend fun refresh(collectiveId: Long): ApiResult<Unit> =
            apiCall {
                // Issue #20: captured before the requests go out, checked
                // inside the transaction that writes their response. A wipe
                // landing in between abandons the write rather than
                // resurrecting the outgoing account's pages under the
                // incoming one — which matters more here than anywhere,
                // because `PageEntity` keys on the raw *server* id and two
                // servers will happily both have a page 17.
                val generation = accountGeneration.current()
                val now = System.currentTimeMillis()
                // R-48: the tag lookup and the page list are independent
                // requests, so they go out together. Sequentially they
                // doubled the latency of a path `FullSync` walks once per
                // collective and that every create / move / rename / copy /
                // restore triggers again.
                val (tagNames, response) = coroutineScope {
                    val tags = async { fetchTagNamesById(collectiveId) }
                    val pages = async { api.listPages(collectiveId) }
                    tags.await() to pages.await()
                }
                // R-27: bulk-load existing rows for the collective in one
                // query, then look up locally in the map. The previous
                // `pageDao.getById(dto.id)` per DTO was a Room round-trip
                // per page — for a 200-page collective that's 200 queries
                // on every refresh.
                val existingById = pageDao.listForCollective(collectiveId).associateBy { it.id }
                val entities = response.ocs.data.pages.map { dto ->
                    val existing = existingById[dto.id]
                    dto.toEntity(
                        collectiveId = collectiveId,
                        now = now,
                        existingBody = existing?.bodyMd,
                        existingEtag = existing?.bodyEtag,
                        existingDraft = existing?.draftBodyMd,
                        existingTagsCsv = existing?.tagsCsv,
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
                    if (!accountGeneration.isCurrent(generation)) {
                        Timber.i("Account changed mid-sync; abandoning the page refresh")
                        return@withTransaction
                    }
                    pageDao.upsertAll(entities)
                    val keepIds = entities.map { it.id }
                    val keepSet = keepIds.toSet()
                    // B-66: the rows about to be dropped have to be cascaded
                    // by hand — see [cascadeForPages]. Read the ids after the
                    // upsert so a page the server just added isn't mistaken
                    // for one going away.
                    cascadeForPages(pageDao.idsForCollective(collectiveId).filterNot { it in keepSet })
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
         * failure here doesn't break the page list — the refresh carries on
         * against the tags already cached.
         *
         * B-69: null, not an empty map, on failure. `PageDto.toEntity` writes
         * whatever it resolves straight into `tagsCsv`, so the old empty-map
         * fallback meant one 500 from the tags endpoint blanked the tags on
         * every page of the collective — and Tag Browse with them — until a
         * later refresh happened to succeed. Null lets the mapper tell "no
         * tags" from "don't know".
         */
        private suspend fun fetchTagNamesById(collectiveId: Long): Map<Long, String>? {
            val result = apiCall {
                api
                    .listTags(collectiveId)
                    .ocs.data.tags
            }
            return if (result is ApiResult.Success) {
                result.data.associate { it.id to it.name }
            } else {
                Timber.w("Tag lookup failed for collective %d; keeping the cached tags", collectiveId)
                null
            }
        }

        /**
         * Carry [old]'s local-only state onto the id [moved] came back with —
         * issue #39.
         *
         * `PUT /pages/{id}` can hand a page a *new* Nextcloud file id
         * (`CollectivesApiService.updatePage`, spec gotcha #16), and both
         * `renamePage` and `movePage` used to throw the response away and let
         * `refresh` sort it out. Refresh maps local state onto server pages
         * strictly by numeric id, so a reissued id arrived as a page with no
         * history; the old id then looked like a page the server had deleted,
         * and [cascadeForPages] removed its attachment and edit-queue rows.
         * A rename could therefore discard a conflict draft, an offline edit
         * waiting for the network, or the only database pointers to staged
         * upload bytes — silently, on a operation the user thinks of as
         * cosmetic.
         *
         * One transaction, so the row never exists under both ids and never
         * under neither. Built from the response rather than `old.copy` so
         * the title, path and parent are the server's own answer: `refresh`
         * follows and would correct them, but it needs the network and this
         * must not depend on it.
         *
         * A no-op — the overwhelmingly common case — when the id came back
         * unchanged.
         */
        private suspend fun carryLocalStateToNewId(
            old: PageEntity,
            moved: PageDto,
        ) {
            if (moved.id == old.id) return
            Timber.i("Page %d was reissued as %d; carrying local state across", old.id, moved.id)
            database.withTransaction {
                pageDao.upsertAll(
                    listOf(
                        moved.toEntity(
                            collectiveId = old.collectiveId,
                            now = System.currentTimeMillis(),
                            existingBody = old.bodyMd,
                            existingEtag = old.bodyEtag,
                            existingDraft = old.draftBodyMd,
                            existingTagsCsv = old.tagsCsv,
                            // B-69: null is "we didn't resolve the tag list",
                            // which is right — `existingTagsCsv` above is what
                            // this page's tags are until `refresh` says
                            // otherwise. An empty map would blank them.
                            tagNamesById = null,
                        ),
                    ),
                )
                editQueueDao.repointPage(old.id, moved.id)
                attachmentRepository.get().rekeyForPage(old.id, moved.id)
                pageDao.deleteById(old.id)
            }
        }

        /**
         * Delete everything keyed to [pageIds] that Room won't.
         *
         * B-66: no entity in this schema declares a foreign key (verified
         * against `app/schemas/…/7.json`), so `ON DELETE CASCADE` doesn't
         * exist here — a dropped `pages` row leaves its `attachments` rows
         * behind for good, including staged-upload rows that
         * `AttachmentUploadWorker.pendingUploads()` keeps selecting, and its
         * `edit_queue` row, which the flush worker only clears the next time
         * it happens to run.
         *
         * Caller must already hold a transaction.
         */
        private suspend fun cascadeForPages(pageIds: List<Long>) {
            if (pageIds.isEmpty()) return
            attachmentDao.deleteForPageIds(pageIds)
            editQueueDao.deleteForPageIds(pageIds)
        }

        override suspend fun getPage(pageId: Long): Page? = pageDao.getById(pageId)?.toDomain(editQueueDao.pendingBody(pageId))

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

                is ApiResult.NetworkError -> {
                    result
                }

                is ApiResult.HttpError -> {
                    result
                }

                ApiResult.Unauthorised -> {
                    ApiResult.Unauthorised
                }

                ApiResult.Conflict -> {
                    ApiResult.Conflict
                }

                is ApiResult.Unexpected -> {
                    result
                }
            }
        }

        override suspend fun refreshBodyIfChanged(pageId: Long): ApiResult<Boolean> {
            val entity = pageDao.getById(pageId)
                ?: return ApiResult.Unexpected(IllegalStateException("Page $pageId not cached"))
            val plan = bodyFetchPlan(entity.bodyMd, entity.bodyEtag)
            if (plan !is BodyFetchPlan.Revalidate) {
                return fetchBody(pageId).mapSuccess { true }
            }
            val result = bodyService.fetchBodyIfChanged(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = entity.fileName,
                knownEtag = plan.etag,
            )
            return when (result) {
                is ApiResult.Success -> {
                    when (val body = result.data) {
                        ConditionalBody.NotModified -> {
                            ApiResult.Success(false)
                        }

                        is ConditionalBody.Modified -> {
                            // Deliberately unconditional on the edit queue: a
                            // queued offline edit lives in `edit_queue`, not on
                            // the page row, and `EditFlushWorker` compares the
                            // server etag against the entry's own `baseEtag`.
                            // Advancing the row here therefore can't lose a
                            // pending edit — and it stops the user editing text
                            // the server has already replaced, which is how a
                            // stale row turned every first save into a 412.
                            pageDao.updateBody(
                                pageId,
                                body.body.markdown,
                                body.body.etag,
                                System.currentTimeMillis(),
                            )
                            ApiResult.Success(true)
                        }
                    }
                }

                is ApiResult.NetworkError -> {
                    result
                }

                is ApiResult.HttpError -> {
                    result
                }

                ApiResult.Unauthorised -> {
                    ApiResult.Unauthorised
                }

                ApiResult.Conflict -> {
                    ApiResult.Conflict
                }

                is ApiResult.Unexpected -> {
                    result
                }
            }
        }

        override suspend fun saveBody(
            pageId: Long,
            newBody: String,
        ): SaveOutcome {
            val entity = pageDao.getById(pageId)
                ?: return SaveOutcome.Error("Page not cached")
            // Issue #29: the precondition comes from the queue row when there
            // is one, not from the page row. See `savePrecondition`.
            val existing = editQueueDao.forPage(pageId)
            val precondition = savePrecondition(existing, entity.bodyEtag)
            val result = bodyService.saveBody(
                collectivePath = entity.collectivePath,
                filePath = entity.filePath,
                fileName = entity.fileName,
                body = newBody,
                baseEtag = precondition.baseEtag,
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
                    if (existing?.status == "CONFLICTED") {
                        SaveOutcome.Conflict
                    } else {
                        // Issue #18: `newBody` already contains whatever was
                        // queued before, because the editor and the append
                        // path both read the queued body back. What must not
                        // be lost in the replacement is the metadata saying
                        // what the edit chain is written against — see
                        // `coalesceQueuedEdit`.
                        editQueueDao.upsert(
                            coalesceQueuedEdit(
                                existing = existing,
                                pageId = pageId,
                                serverEtag = entity.bodyEtag,
                                newBody = newBody,
                                now = System.currentTimeMillis(),
                            ),
                        )
                        syncScheduler.flushEditsWhenOnline()
                        SaveOutcome.Queued
                    }
                }

                ApiResult.Conflict -> {
                    // B-67: leave exactly the state `EditFlushWorker`'s
                    // conflict path leaves, not just the draft.
                    //
                    // Two things were missing. Without a `CONFLICTED` queue
                    // row the B-19 guard above never arms for this path, so
                    // the next offline save on this page queues happily —
                    // with the stale `baseEtag` — and its flush overwrites
                    // the draft this branch just kept. And leaving `bodyEtag`
                    // at the value the server has already rejected guarantees
                    // the next online save is another 412; refetching the
                    // body is also what lets the user see what they're
                    // conflicting with.
                    val fresh = when (
                        val server = bodyService.fetchBody(
                            collectivePath = entity.collectivePath,
                            filePath = entity.filePath,
                            fileName = entity.fileName,
                        )
                    ) {
                        is ApiResult.Success -> server.data

                        // Offline again already, or the refetch failed: the
                        // draft and the conflict marker still land, and the
                        // next revalidation on open advances the body.
                        else -> null
                    }
                    database.withTransaction {
                        if (fresh != null) {
                            pageDao.updateBody(
                                pageId,
                                fresh.markdown,
                                fresh.etag,
                                System.currentTimeMillis(),
                            )
                        }
                        pageDao.updateDraft(pageId, newBody)
                        editQueueDao.upsert(
                            EditQueueEntity(
                                pageId = pageId,
                                // The etag the draft would have to be written
                                // against, as far as we know it. Only used if
                                // the row is ever re-armed — a CONFLICTED row
                                // is never picked up by `pendingEntries`.
                                baseEtag = fresh?.etag ?: entity.bodyEtag,
                                newBodyMd = newBody,
                                queuedAt = System.currentTimeMillis(),
                                status = "CONFLICTED",
                            ),
                        )
                    }
                    SaveOutcome.Conflict
                }

                ApiResult.Unauthorised -> {
                    SaveOutcome.Error(result.userMessage() ?: "Unauthorised")
                }

                is ApiResult.HttpError -> {
                    SaveOutcome.Error(result.userMessage() ?: "Server error")
                }

                is ApiResult.Unexpected -> {
                    SaveOutcome.Error(result.userMessage() ?: "Unexpected error")
                }
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

                ApiResult.Conflict -> {
                    SaveOutcome.Conflict
                }

                ApiResult.Unauthorised -> {
                    SaveOutcome.Error(result.userMessage() ?: "Unauthorised")
                }

                is ApiResult.HttpError -> {
                    SaveOutcome.Error(result.userMessage() ?: "Server error")
                }

                is ApiResult.Unexpected -> {
                    SaveOutcome.Error(result.userMessage() ?: "Unexpected error")
                }
            }
        }

        override suspend fun discardDraft(pageId: Long) {
            // B-67: the queue row goes with the draft. The user has thrown
            // the text away, and a `CONFLICTED` row left behind would keep
            // the B-19 guard armed forever — every later offline save on this
            // page would report a conflict for a draft that no longer exists.
            database.withTransaction {
                pageDao.updateDraft(pageId, null)
                editQueueDao.deleteForPage(pageId)
            }
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
            }.mapSuccess { tags ->
                // S-18: sanitise + drop tags that come back nameless after
                // sanitisation. A nameless tag is unusable as a route arg
                // and would lose its identity in the LIKE post-filter
                // anyway, so emit nothing rather than a bare empty chip.
                tags.mapNotNull { dto ->
                    val clean = ServerStringValidation.sanitiseDisplay(dto.name)
                    if (clean.isEmpty()) null else PageTag(id = dto.id, name = clean)
                }
            }

        override suspend fun createTag(
            collectiveId: Long,
            name: String,
            color: String,
        ): ApiResult<PageTag> =
            apiCall {
                api
                    .createTag(collectiveId, name, color)
                    .ocs.data.tag
            }.mapSuccess { dto ->
                // S-18: same gate as listTagsForCollective. If the server
                // accepts a tag with only control characters, fail
                // explicitly rather than minting a nameless PageTag.
                val clean = ServerStringValidation.sanitiseDisplay(dto.name)
                PageTag(id = dto.id, name = clean.ifEmpty { dto.name })
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
                carryLocalStateToNewId(entity, result.data.ocs.data.page)
                // Refresh the collective to pick up any cascading filePath
                // changes on descendants (folder rename moves the whole
                // directory) and to reconcile whatever else moved.
                refresh(entity.collectiveId)
            }
            return result.mapSuccess { }
        }

        override suspend fun createPage(
            collectiveId: Long,
            parentPageId: Long,
            title: String,
            body: String,
        ): ApiResult<PageCreation> {
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
                        // B-69: explicitly "resolved against no tags", not
                        // "lookup failed" — a page the server just created
                        // has none. The `refresh` below re-resolves anyway.
                        tagNamesById = emptyMap(),
                    ),
                ),
            )
            refresh(collectiveId)
            // The OCS POST creates an empty page; its markdown is a separate
            // WebDAV write. Issue #25: that write goes through `saveBody`
            // now, so a failure *queues* the body instead of being handed
            // back as an error.
            //
            // The old shape returned only the error and dropped the created
            // page's id with it, so the caller — a share capture, typically
            // — had a page on the server it could no longer name, and its
            // retry created a second one. Queueing means the body is
            // durably the user's, `EditFlushWorker` carries it, and the
            // caller gets the page either way. It also gives the initial
            // body the same offline story every other save in the app has.
            val bodyOutcome = if (body.isEmpty()) {
                SaveOutcome.Saved
            } else {
                // Issue #31: whatever this says is handed back rather than
                // logged. `saveBody` queues a dropped network, but a 408, a
                // 5xx or a permission failure comes back as an Error — and
                // swallowing that left an empty remote page while the caller
                // reported a clean capture.
                saveBody(createdDto.id, body)
            }
            val saved = pageDao.getById(createdDto.id)
                ?: return ApiResult.Unexpected(
                    IllegalStateException("Page ${createdDto.id} disappeared from cache after create"),
                )
            return ApiResult.Success(PageCreation(page = saved.toDomain(), bodyOutcome = bodyOutcome))
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
                // — see B-9 in the audit findings. B-66: with the page row
                // gone, its attachments and any queued edit are unreachable
                // rows nothing will ever clean up, so cascade by hand. The
                // page is recoverable from the server-side trash; a queued
                // local edit to it isn't, but it couldn't have been flushed
                // either — the file has moved out from under its WebDAV path.
                database.withTransaction {
                    cascadeForPages(listOf(pageId))
                    pageDao.deleteById(pageId)
                }
            }
            return result
        }

        override suspend fun listTrashedPages(collectiveId: Long): ApiResult<List<Page>> =
            apiCall {
                // R-48: same two independent requests as `refresh`, same fix.
                val (tagNames, envelope) = coroutineScope {
                    val tags = async { fetchTagNamesById(collectiveId) }
                    val pages = async { api.listTrashedPages(collectiveId) }
                    tags.await() to pages.await()
                }
                val now = System.currentTimeMillis()
                envelope.ocs.data.pages.map { dto ->
                    dto
                        .toEntity(
                            collectiveId = collectiveId,
                            now = now,
                            existingBody = null,
                            existingEtag = null,
                            existingDraft = null,
                            // These rows are never cached — they exist to
                            // render the trash screen — so there is no
                            // existing `tagsCsv` to preserve. A failed tag
                            // lookup shows the entries without chips.
                            tagNamesById = tagNames,
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
        ): Flow<List<Page>> {
            // R-57: narrow to the rows that mention the id in SQL instead
            // of walking the whole collective in Kotlin. `linkedPageIdsCsv`
            // is only ever written by `toLongCsv`, so it is bare digits and
            // commas — wrapping the column in separators on both sides lets
            // one LIKE match the id whether it sits first, last, alone or
            // in the middle, and can't confuse `5` with `15`.
            val likePattern = "%$ID_CSV_SEP$pageId$ID_CSV_SEP%"
            return pageDao.observeBacklinksIn(collectiveId, ID_CSV_SEP, likePattern).map { rows ->
                // Same exact-match filter as before, over the parsed ids:
                // the LIKE only has to narrow, and a page that links to
                // itself still mustn't appear in its own backlinks.
                rows
                    .asSequence()
                    .filter { row -> row.id != pageId && pageId in row.linkedPageIdsCsv.toLongCsvList() }
                    .map { it.toDomain() }
                    .toList()
            }
        }

        override fun observePagesWithTagInCollective(
            collectiveId: Long,
            tagName: String,
        ): Flow<List<PageListItem>> {
            // B-53: escape `%`/`_`/`\\` in the tag name so they don't act
            // as LIKE wildcards. Worst case before this fix was
            // `tagName = "%"` matching every tagged row and loading the
            // whole collective into the post-filter. The DAO query carries
            // `ESCAPE '\\'` to pair with this escaper.
            val escapedTag = tagName
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val likePattern = "%$TAG_SEP_STRING$escapedTag$TAG_SEP_STRING%"
            return pageDao
                .observePagesWithTagInCollective(collectiveId, TAG_SEP_STRING, likePattern)
                // R-55: as in `observePageList` — a body write re-runs this
                // query too, and nothing it returns depends on a body.
                .distinctUntilChanged()
                .map { rows ->
                    // Defence in depth: exact-match filter against the
                    // unescaped name absorbs any LIKE corner case we
                    // missed and the (rare) case where a tag was
                    // reordered into a different CSV slot mid-query.
                    rows
                        .filter { tagName in splitTags(it.tagsCsv) }
                        .map { it.toDomain() }
                }
        }

        override suspend fun resolvePageByTitle(
            collectiveId: Long,
            title: String,
        ): Long? {
            // R-32: case-insensitive `.md` strip (previous double
            // `removeSuffix` only caught `.md` and `.MD`, slipping `.Md`).
            val trimmed = title.trim()
            val withoutMd = if (trimmed.endsWith(".md", ignoreCase = true)) {
                trimmed.dropLast(3)
            } else {
                trimmed
            }
            val cleaned = withoutMd.trim()
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
            //
            // Issue #18: a queued edit outranks the row's cached body, which
            // is the server's. Appending to the row instead meant a share
            // into a page edited offline dropped that edit — and it must not
            // fall through to `fetchBody` either, since the queue only holds
            // anything when the network already refused us.
            val baseBody = editQueueDao.pendingBody(pageId)
                ?: entity.bodyMd
                ?: run {
                    val fetched = fetchBody(pageId)
                    if (fetched !is ApiResult.Success) {
                        return SaveOutcome.Error(fetched.userMessage() ?: "Couldn't load page body")
                    }
                    fetched.data
                }
            return saveBody(pageId, appendedBody(baseBody, text))
        }

        override suspend fun retargetAttachmentRef(
            pageId: Long,
            oldName: String,
            newName: String,
        ): SaveOutcome {
            if (oldName == newName) return SaveOutcome.Saved
            val entity = pageDao.getById(pageId) ?: return SaveOutcome.Error("Page not cached")
            // Same precedence as `appendToPage`: a queued edit is the local
            // truth about this body, the row's cached copy is the server's,
            // and only a page we have never held the body of needs fetching.
            // Unlike append, a failure to get one is not worth reporting —
            // nothing the user did is waiting on it, and the next refresh of
            // a body that has no local copy will carry the server's own
            // reference anyway.
            val baseBody = editQueueDao.pendingBody(pageId)
                ?: entity.bodyMd
                ?: return SaveOutcome.Error("Page body not cached")
            val retargeted = retargetAttachmentRefs(baseBody, pageId, oldName, newName)
            if (retargeted == baseBody) return SaveOutcome.Saved
            return saveBody(pageId, retargeted)
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
                carryLocalStateToNewId(entity, result.data.ocs.data.page)
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
                    // B-69: as in `createPage` — the copy carries no resolved
                    // tags yet, which is different from an unknown tag list.
                    // The `refresh` below picks up whatever the server copied.
                    tagNamesById = emptyMap(),
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
            val result = apiCall {
                api.setSubpageOrder(collectiveId, parentPageId, subpageOrderIds.toJsonLongArray())
            }
            if (result !is ApiResult.Success) {
                pageDao.updateSubpageOrderCsv(parentPageId, previousCsv)
            }
            return result.mapSuccess { }
        }
    }

/**
 * Separator `toLongCsv` writes id columns with. Named here because R-57's
 * backlink LIKE pattern has to be built out of the same character the
 * column was written with, and a silent mismatch would just quietly return
 * no backlinks.
 */
private const val ID_CSV_SEP = ","

/**
 * What opening a page should do about its markdown body.
 *
 * Note what isn't here: a "leave it alone" arm. Before B-58 the page screen
 * fetched a body only when it had none cached, which meant a page's content
 * was pulled exactly once per install and then never checked again — nothing
 * else in the app re-fetches markdown, so an edit made anywhere else was
 * invisible forever. Every open now either revalidates or fetches.
 */
internal sealed interface BodyFetchPlan {
    /** Nothing cached to validate against — ask for the whole body. */
    data object FetchWhole : BodyFetchPlan

    /** Ask the server whether [etag] is still current. */
    data class Revalidate(
        val etag: String,
    ) : BodyFetchPlan
}

/**
 * A cached body with no ETag can't be revalidated — the etag column only
 * populates on a fetch, so a body without one predates that or came from a
 * server that didn't send one. Fetch it whole and pick up an ETag for next
 * time.
 */
internal fun bodyFetchPlan(
    bodyMd: String?,
    bodyEtag: String?,
): BodyFetchPlan =
    if (bodyMd != null && bodyEtag != null) {
        BodyFetchPlan.Revalidate(bodyEtag)
    } else {
        BodyFetchPlan.FetchWhole
    }

/** What a foreground save should write against. See [savePrecondition]. */
internal data class SavePrecondition(
    val baseEtag: String?,
    val forceWrite: Boolean,
)

/**
 * The `If-Match` value a foreground save should carry.
 *
 * Issue #29, and a regression the reader-side overlay in #18 introduced. The
 * precondition used to come straight off the page row, which is wrong as soon
 * as a queued edit exists, because the two describe different things: the
 * page row's ETag is *the server's current version*, and the queued body was
 * written against whatever the chain started from.
 *
 * The sequence that lost data: fetch at ETag A, go offline, edit and save
 * (queued with baseEtag A), another client writes and the server moves to
 * ETag B, come back online and reopen the page. Revalidation advances the row
 * to B — deliberately, and #18 made the editor keep showing the *queued*
 * body over it. Press Save and the queued body went out with `If-Match: B`,
 * which succeeds, silently discarding the other client's edit and deleting
 * the queue row that held the only evidence. Before #18 the editor had shown
 * the server's text instead, so the same keypress wrote B back over B and the
 * flush worker later reported the conflict properly.
 *
 * So a non-conflicted queue row's own metadata wins, exactly as
 * `EditFlushWorker` uses it: its `baseEtag`, and `forceWrite` collapsing the
 * precondition to none because the user has already chosen to override it
 * (B-46). A 412 then routes into the conflict branch, which parks the text as
 * a draft — recoverable, and visible.
 *
 * A `CONFLICTED` row is deliberately not used. Its draft is already on the
 * page row beside the server's body, the user has been shown both, and the
 * page's ETag is the version they were shown.
 */
internal fun savePrecondition(
    queued: EditQueueEntity?,
    pageEtag: String?,
): SavePrecondition =
    when {
        queued == null || queued.status == "CONFLICTED" -> SavePrecondition(pageEtag, forceWrite = false)
        queued.forceWrite -> SavePrecondition(baseEtag = null, forceWrite = true)
        else -> SavePrecondition(queued.baseEtag, forceWrite = false)
    }

/**
 * The queue row a fresh offline save should leave behind, given the row
 * already there (if any).
 *
 * `EditQueueEntity.pageId` is the primary key and the write is an `@Upsert`,
 * so a second offline save *replaces* the first row rather than adding one.
 * That is the right shape — every reader overlays the queued body, so
 * [newBody] already contains the earlier edit — but only if the metadata
 * describing what the edit is written *against* survives the replacement.
 * Issue #18:
 *
 *  - [EditQueueEntity.baseEtag] stays the ETag the chain started from rather
 *    than being re-read off the page row. A null on an existing row is
 *    meaningful — a force-write, or a server that sends no `ETag` — and
 *    healing it back to the page's ETag would re-arm an `If-Match` the user
 *    has already overridden.
 *  - [EditQueueEntity.forceWrite] is sticky. Once the user has chosen
 *    "replace with my draft", the edits they go on to make are still theirs
 *    to force; dropping the flag hands the next flush a precondition to fail
 *    and turns their override back into a conflict (B-46).
 *  - [EditQueueEntity.queuedAt] keeps the earliest time, so one page being
 *    edited over and over can't keep pushing itself behind older pages in
 *    `pendingEntries`' ordering.
 *
 * `status` is deliberately reset to `PENDING`: the caller has already
 * refused to queue on top of a `CONFLICTED` row, so the only status this can
 * overwrite is `IN_FLIGHT` — a row whose PUT is in progress, which the
 * worker re-reads before settling (`settledQueueRow`).
 */
internal fun coalesceQueuedEdit(
    existing: EditQueueEntity?,
    pageId: Long,
    serverEtag: String?,
    newBody: String,
    now: Long,
): EditQueueEntity =
    EditQueueEntity(
        pageId = pageId,
        baseEtag = if (existing != null) existing.baseEtag else serverEtag,
        newBodyMd = newBody,
        queuedAt = existing?.queuedAt ?: now,
        status = "PENDING",
        forceWrite = existing?.forceWrite ?: false,
    )

/**
 * [text] appended to [base] as a fresh markdown block.
 *
 * Two newlines, not one: an append to a body ending in `# Heading` would
 * otherwise produce `# Heading\nshared text`, which parses *inside* the
 * heading (B-16). The blank line forces a new block.
 */
internal fun appendedBody(
    base: String,
    text: String,
): String =
    when {
        base.isEmpty() -> text
        base.endsWith("\n\n") -> base + text
        base.endsWith("\n") -> base + "\n" + text
        else -> base + "\n\n" + text
    }
