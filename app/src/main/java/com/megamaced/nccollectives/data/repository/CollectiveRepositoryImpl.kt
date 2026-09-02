package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.ifSuccess
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.toJsonLongArray
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.CollectiveMember
import com.megamaced.nccollectives.domain.model.DEFAULT_MEMBER_LIMIT
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectiveRepositoryImpl
    @Inject
    constructor(
        private val api: CollectivesApiService,
        private val circlesApi: CirclesApiService,
        private val dao: CollectiveDao,
        private val pageDao: PageDao,
        private val attachmentDao: AttachmentDao,
        private val editQueueDao: EditQueueDao,
        private val database: NcCollectivesDatabase,
        private val accountGeneration: AccountGeneration,
    ) : CollectiveRepository {
        override fun observeCollectives(): Flow<List<Collective>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        override suspend fun cachedCollectives(): List<Collective> = dao.list().map { it.toDomain() }

        override suspend fun refresh(): ApiResult<Unit> =
            apiCall {
                // Issue #20: captured before the request goes out, checked
                // inside the transaction that writes its response, so a wipe
                // landing in between abandons the write instead of
                // resurrecting the outgoing account's collectives under the
                // incoming one.
                val generation = accountGeneration.current()
                val now = System.currentTimeMillis()
                val response = api.listCollectives()
                val entities = response.ocs.data.collectives
                    .map { it.toEntity(now) }
                // B-43: one transaction. B-42: short-circuit the empty-list
                // case to `clear()` to avoid `WHERE id NOT IN ()` SQL.
                database.withTransaction {
                    if (!accountGeneration.isCurrent(generation)) {
                        Timber.i("Account changed mid-sync; abandoning the collective refresh")
                        return@withTransaction
                    }
                    dao.upsertAll(entities)
                    val keepIds = entities.map { it.id }
                    val keepSet = keepIds.toSet()
                    // B-65: a collective that stopped being shared with the
                    // user just isn't in the response any more, so this is the
                    // path that fires — and it has to cascade. Work out what
                    // is going away before deleting it, since neither
                    // `deleteMissing` nor `clear` can tell us afterwards.
                    //
                    // `dao.list()` only returns non-trashed rows. That covers
                    // the table in practice: `listCollectives` returns active
                    // collectives, `listTrashedCollectives` never caches, so
                    // nothing ever writes a row with a `trashTimestamp`. If
                    // that ever changes, a trashed row would be deleted here
                    // without its pages — as it already was before this fix.
                    cascadeForCollectives(dao.list().map { it.id }.filterNot { it in keepSet })
                    if (keepIds.isEmpty()) {
                        dao.clear()
                    } else {
                        dao.deleteMissing(keepIds)
                    }
                }
            }

        override suspend fun toggleFavorite(
            collectiveId: Long,
            pageId: Long,
            favorite: Boolean,
        ): ApiResult<Unit> {
            val current = dao.getById(collectiveId) ?: return ApiResult.Unexpected(
                IllegalStateException("Collective $collectiveId not cached"),
            )
            val currentList = current.userFavoritePagesCsv.toLongCsvList()
            val nextList = if (favorite) {
                if (pageId in currentList) currentList else currentList + pageId
            } else {
                currentList - pageId
            }
            if (nextList == currentList) return ApiResult.Success(Unit)

            // Optimistic local update so the UI reflects the new state
            // immediately. Roll back on failure.
            dao.updateFavoritePagesCsv(collectiveId, nextList.joinToString(","))
            val result = apiCall {
                api.setFavoritePages(collectiveId, nextList.toJsonLongArray())
            }
            if (result !is ApiResult.Success) {
                dao.updateFavoritePagesCsv(collectiveId, current.userFavoritePagesCsv)
            }
            return result
        }

        override suspend fun createCollective(
            name: String,
            emoji: String?,
        ): ApiResult<Collective> {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) {
                return ApiResult.Unexpected(IllegalArgumentException("Collective name must not be blank"))
            }
            val now = System.currentTimeMillis()
            return apiCall {
                api.createCollective(trimmedName, emoji?.takeIf { it.isNotBlank() })
            }.mapSuccess { envelope ->
                val dto = envelope.ocs.data.collective
                dao.upsert(dto.toEntity(now))
                dto.toEntity(now).toDomain()
            }
        }

        override suspend fun setCollectiveEmoji(
            collectiveId: Long,
            emoji: String,
        ): ApiResult<Unit> {
            val current = dao.getById(collectiveId)
                ?: return ApiResult.Unexpected(
                    IllegalStateException("Collective $collectiveId not cached"),
                )
            // Empty string clears the emoji server-side (matches the page
            // emoji endpoint and the EmojiPickerSheet "Clear" button).
            val previousEmoji = current.emoji
            val nextEmoji = emoji.takeIf { it.isNotBlank() }
            if (nextEmoji == previousEmoji) return ApiResult.Success(Unit)

            dao.updateEmoji(collectiveId, nextEmoji)
            val result = apiCall { api.setCollectiveEmoji(collectiveId, emoji) }
            if (result !is ApiResult.Success) {
                dao.updateEmoji(collectiveId, previousEmoji)
            }
            return result.mapSuccess { }
        }

        override suspend fun trashCollective(collectiveId: Long): ApiResult<Unit> {
            val result = apiCall { api.trashCollective(collectiveId) }
            if (result is ApiResult.Success) {
                // Drop the row directly. The local cache only carries
                // non-trashed collectives (`observeAll` filters
                // trashTimestamp IS NULL), so the simplest reconciliation
                // is to remove the row — the user can find it from the
                // collective-trash screen if they want to restore it.
                //
                // B-65: and take its pages with it, or they're orphans no
                // path will ever clean up — and a surviving `pages` row is
                // what lets `EditFlushWorker` keep retrying an edit against
                // a collective the user has thrown away. Restoring pulls the
                // pages back from the server on next open; an unflushed
                // queued edit for one of them does go, which is the honest
                // trade: it could never have been written anyway, since its
                // WebDAV path moved into the server-side trash with the
                // collective.
                database.withTransaction {
                    cascadeForCollectives(listOf(collectiveId))
                    dao.deleteById(collectiveId)
                }
            }
            return result.mapSuccess { }
        }

        override suspend fun listTrashedCollectives(): ApiResult<List<Collective>> {
            val now = System.currentTimeMillis()
            return apiCall { api.listTrashedCollectives() }.mapSuccess { envelope ->
                envelope.ocs.data.collectives
                    .map { it.toEntity(now).toDomain() }
            }
        }

        override suspend fun restoreTrashedCollective(collectiveId: Long): ApiResult<Unit> {
            val result = apiCall { api.restoreTrashedCollective(collectiveId) }
            return result
                .ifSuccess {
                    // Pick up the restored collective in the active-list cache.
                    refresh()
                }.mapSuccess { }
        }

        override suspend fun permanentlyDeleteCollective(collectiveId: Long): ApiResult<Unit> {
            val result = apiCall {
                api.permanentlyDeleteCollective(collectiveId, circle = true)
            }
            if (result is ApiResult.Success) {
                database.withTransaction {
                    cascadeForCollectives(listOf(collectiveId))
                    dao.deleteById(collectiveId)
                }
            }
            return result.mapSuccess { }
        }

        override suspend fun listMembers(
            circleId: String,
            limit: Int,
        ): ApiResult<List<CollectiveMember>> {
            if (circleId.isBlank()) {
                // Not a network failure — `Collective.circleId` is nullable
                // and an older server simply doesn't send one, so a caller
                // reaching here with a blank id has a UI-state bug, not a
                // connectivity problem. Sending it would request
                // `/circles//members`, a different route whose answer would
                // be misleading.
                return ApiResult.Unexpected(
                    IllegalArgumentException("Collective has no circleId; membership is unavailable"),
                )
            }
            // A non-positive limit reads as "no limit" server-side, which is
            // the ~440 KB unbounded response the cap exists to prevent. Fall
            // back to the documented default rather than honouring it.
            val effectiveLimit = if (limit > 0) limit else DEFAULT_MEMBER_LIMIT
            // No Room write, and no retry. Members are deliberately
            // uncached (see `CollectiveRepository.listMembers`), and a 403
            // here is both the normal answer for a non-member and a
            // `throttle()` against the user's own IP if hammered — so the
            // `HttpError` arm goes straight back to the caller.
            return apiCall {
                circlesApi.listMembers(circleId, effectiveLimit)
            }.mapSuccess { envelope ->
                envelope.ocs.data.map { it.toDomain() }
            }
        }

        /**
         * Delete every locally-cached row hanging off [collectiveIds] — their
         * pages, and those pages' attachments and queued edits. The
         * `collectives` rows themselves are left to the caller, because each
         * delete path reconciles them differently (`deleteById`,
         * `deleteMissing`, `clear`).
         *
         * B-65: no entity in this schema declares a foreign key (verified
         * against `app/schemas/…/7.json`), so there is no `ON DELETE CASCADE`
         * to lean on and every path that removes a collective has to do this
         * by hand. Only `permanentlyDeleteCollective` used to. The cost of
         * missing it isn't just dead rows: a surviving `pages` row is exactly
         * what defeats `EditFlushWorker`'s `page == null` self-heal, so a
         * collective that was unshared left a queued edit retrying against a
         * page the user can no longer see or reach.
         *
         * Caller must already hold a transaction.
         */
        private suspend fun cascadeForCollectives(collectiveIds: List<Long>) {
            for (collectiveId in collectiveIds) {
                val pageIds = pageDao.idsForCollective(collectiveId)
                if (pageIds.isNotEmpty()) {
                    attachmentDao.deleteForPageIds(pageIds)
                    editQueueDao.deleteForPageIds(pageIds)
                }
                pageDao.deleteForCollective(collectiveId)
            }
        }
    }
