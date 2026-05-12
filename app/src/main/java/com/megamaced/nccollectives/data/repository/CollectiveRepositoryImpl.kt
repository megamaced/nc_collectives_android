package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
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
    }
