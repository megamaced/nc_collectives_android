package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.SearchHit

interface SearchRepository {
    suspend fun search(
        term: String,
        limit: Int = 25,
    ): ApiResult<List<SearchHit>>
}
