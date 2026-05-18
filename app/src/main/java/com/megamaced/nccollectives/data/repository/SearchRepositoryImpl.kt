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
            // No cross-collective title fallback (B-17). Two collectives can
            // hold same-titled pages, and a global `findIdByTitle` lookup
            // would navigate to the wrong one. If the server didn't give us
            // a file id we just leave [SearchHit.pageId] null — the result
            // still shows title + snippet but isn't tappable.
            val pageId = pageIdFromAttr ?: pageIdFromUrl
            val collectiveId = pageId?.let { pageDao.getById(it)?.collectiveId }
            return SearchHit(
                title = title,
                snippet = subline?.takeIf { it.isNotBlank() },
                pageId = pageId,
                collectiveId = collectiveId,
            )
        }

        private fun extractFileIdFromQuery(url: String): Long? {
            // B-47: route the parse through `Uri.parse` so query lookup
            // handles fragments (`#anchor`) and percent-encoding correctly.
            // The previous `substringAfter('?') + split('&')` produced
            // `"42#anchor"` for `?fileId=42#anchor` and `toLongOrNull()`
            // returned null — the search hit became untappable.
            val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
            return uri.getQueryParameter("fileId")?.toLongOrNull()
        }
    }
