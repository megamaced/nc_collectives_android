package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Page
import kotlinx.coroutines.flow.Flow

interface PageRepository {
    fun observePages(collectiveId: Long): Flow<List<Page>>

    suspend fun refresh(collectiveId: Long): ApiResult<Unit>

    /** Returns the page (cache hit when available, else fetches metadata). */
    suspend fun getPage(pageId: Long): Page?

    /** Fetches the markdown body over WebDAV and persists it to the page row. */
    suspend fun fetchBody(pageId: Long): ApiResult<String>
}
