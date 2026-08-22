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
            // Collapse every non-Success arm with a single cast.
            return if (raw is ApiResult.Success) {
                ApiResult.Success(toHits(raw.data))
            } else {
                raw as ApiResult<List<SearchHit>>
            }
        }

        /**
         * R-46: resolve every hit's collective in ONE projection query.
         * This used to be a `pageDao.getById(pageId)?.collectiveId` per
         * entry — a `SELECT *` that read the full cached markdown body of
         * every result just to pull one Long out of it.
         */
        private suspend fun toHits(entries: List<SearchEntryDto>): List<SearchHit> {
            val pageIds = entries.map { it.pageId() }
            val known = pageIds.filterNotNull().distinct()
            // Room expands an empty list to `IN ()`, a SQLite syntax error.
            val collectiveIds = if (known.isEmpty()) {
                emptyMap()
            } else {
                pageDao
                    .collectiveIdsForPages(known)
                    .associate { it.id to it.collectiveId }
            }
            return entries.mapIndexed { index, entry ->
                val pageId = pageIds[index]
                SearchHit(
                    title = entry.title,
                    snippet = entry.subline?.takeIf { it.isNotBlank() },
                    pageId = pageId,
                    collectiveId = pageId?.let { collectiveIds[it] },
                )
            }
        }

        /**
         * No cross-collective title fallback (B-17). Two collectives can
         * hold same-titled pages, and a global `findIdByTitle` lookup
         * would navigate to the wrong one. If the server didn't give us a
         * file id we just leave [SearchHit.pageId] null — the result still
         * shows title + snippet but isn't tappable.
         */
        private fun SearchEntryDto.pageId(): Long? =
            attributes["fileId"]?.toLongOrNull()
                ?: resourceUrl?.let { extractFileIdFromQuery(it) }

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
