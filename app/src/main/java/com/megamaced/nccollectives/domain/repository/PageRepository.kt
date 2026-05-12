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

    /**
     * Writes [newBody] back over WebDAV, sending the cached etag as
     * `If-Match`. On success, persists the new body + etag locally and
     * returns the result; on [ApiResult.Conflict] the local row is left
     * untouched (the caller surfaces the mismatch — Batch 8 wires a draft
     * + queue for conflict resolution).
     */
    suspend fun saveBody(
        pageId: Long,
        newBody: String,
    ): ApiResult<Unit>
}
