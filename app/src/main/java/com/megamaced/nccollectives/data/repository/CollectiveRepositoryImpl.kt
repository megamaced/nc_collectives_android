package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
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
    }
