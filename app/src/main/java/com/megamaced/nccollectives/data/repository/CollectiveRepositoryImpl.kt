package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.ifSuccess
import com.megamaced.nccollectives.data.api.mapSuccess
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.toLongCsvList
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectiveRepositoryImpl
    @Inject
    constructor(
        private val api: CollectivesApiService,
        private val dao: CollectiveDao,
        private val pageDao: PageDao,
        private val attachmentDao: AttachmentDao,
        private val editQueueDao: EditQueueDao,
        private val database: NcCollectivesDatabase,
    ) : CollectiveRepository {
        override fun observeCollectives(): Flow<List<Collective>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        override suspend fun refresh(): ApiResult<Unit> =
            apiCall {
                val now = System.currentTimeMillis()
                val response = api.listCollectives()
                val entities = response.ocs.data.collectives
                    .map { it.toEntity(now) }
                dao.upsertAll(entities)
                dao.deleteMissing(entities.map { it.id })
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
                api.setFavoritePages(collectiveId, encodeFavorites(nextList))
            }
            if (result !is ApiResult.Success) {
                dao.updateFavoritePagesCsv(collectiveId, current.userFavoritePagesCsv)
            }
            return result
        }

        private fun encodeFavorites(ids: List<Long>): String = ids.joinToString(prefix = "[", postfix = "]", separator = ",")

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
                dao.deleteById(collectiveId)
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
                // Cascade every locally-cached row tied to the collective so
                // the user doesn't see stale pages / attachments / queued
                // edits if they happen to come back to a re-created
                // collective that re-uses the same id (unlikely but cheap
                // insurance) — and more importantly so we don't keep blob
                // rows around indefinitely.
                database.withTransaction {
                    val pageIds = pageDao.idsForCollective(collectiveId)
                    if (pageIds.isNotEmpty()) {
                        attachmentDao.deleteForPageIds(pageIds)
                        editQueueDao.deleteForPageIds(pageIds)
                    }
                    pageDao.deleteForCollective(collectiveId)
                    dao.deleteById(collectiveId)
                }
            }
            return result.mapSuccess { }
        }
    }
