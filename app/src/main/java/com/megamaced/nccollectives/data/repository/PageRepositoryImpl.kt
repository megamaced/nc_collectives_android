package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.PageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageRepositoryImpl
    @Inject
    constructor(
        private val api: CollectivesApiService,
        private val dao: PageDao,
    ) : PageRepository {
        override fun observePages(collectiveId: Long): Flow<List<Page>> =
            dao.observeForCollective(collectiveId).map { rows -> rows.map { it.toDomain() } }

        override suspend fun refresh(collectiveId: Long): ApiResult<Unit> =
            apiCall {
                val now = System.currentTimeMillis()
                val response = api.listPages(collectiveId)
                val entities = response.ocs.data.pages.map { dto ->
                    val existingBody = dao.getById(dto.id)?.bodyMd
                    dto.toEntity(collectiveId, now, existingBody)
                }
                dao.upsertAll(entities)
                dao.deleteMissingForCollective(collectiveId, entities.map { it.id })
            }
    }
