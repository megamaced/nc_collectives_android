package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.SearchHit

interface SearchRepository {
    /** Delivers title hits before loading body matches, then returns the combined result. */
    suspend fun search(
        term: String,
        limit: Int = 25,
        onTitleHits: (List<SearchHit>) -> Unit = {},
    ): ApiResult<List<SearchHit>>
}
