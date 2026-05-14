package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.SearchApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.dto.SearchEntryDto
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.domain.model.SearchHit
import com.megamaced.nccollectives.domain.repository.SearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepositoryImpl
    @Inject
    constructor(
        private val api: SearchApiService,
        private val pageDao: PageDao,
    ) : SearchRepository {
        @Suppress("UNCHECKED_CAST")
        override suspend fun search(
            term: String,
            limit: Int,
        ): ApiResult<List<SearchHit>> {
            val trimmed = term.trim()
            if (trimmed.isEmpty()) return ApiResult.Success(emptyList())
            val raw = apiCall {
                api
                    .searchPages(trimmed, limit)
                    .ocs.data.entries
            }
            // .map { } can't be used here because toHit() is suspend; collapse
            // every non-Success arm with a single cast.
            return if (raw is ApiResult.Success) {
                ApiResult.Success(raw.data.map { it.toHit() })
            } else {
                raw as ApiResult<List<SearchHit>>
            }
        }

        private suspend fun SearchEntryDto.toHit(): SearchHit {
            val pageIdFromAttr = attributes["fileId"]?.toLongOrNull()
            val pageIdFromUrl = resourceUrl?.let { extractFileIdFromQuery(it) }
            val resolvedId = pageIdFromAttr ?: pageIdFromUrl
            // Fall back: scan the local cache for an exact title match. Works
            // for the common case where the page is already cached locally.
            val viaTitle = if (resolvedId == null) pageDao.findIdByTitle(title) else null
            val pageId = resolvedId ?: viaTitle
            val collectiveId = pageId?.let { pageDao.getById(it)?.collectiveId }
            return SearchHit(
                title = title,
                snippet = subline?.takeIf { it.isNotBlank() },
                pageId = pageId,
                collectiveId = collectiveId,
            )
        }

        private fun extractFileIdFromQuery(url: String): Long? {
            val q = url.substringAfter('?', "")
            if (q.isEmpty()) return null
            return q
                .split('&')
                .firstOrNull { it.startsWith("fileId=") }
                ?.removePrefix("fileId=")
                ?.toLongOrNull()
        }
    }
