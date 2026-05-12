package com.megamaced.nccollectives.domain.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import kotlinx.coroutines.flow.Flow

interface CollectiveRepository {
    /** Emits the cached list immediately, then updates as the cache changes. */
    fun observeCollectives(): Flow<List<Collective>>

    /** Force a network refresh; the resulting list lands in the Flow above. */
    suspend fun refresh(): ApiResult<Unit>

    /**
     * Add or remove [pageId] from the user's favorites for [collectiveId].
     * Applies optimistically to Room first, rolls the cache back on failure.
     */
    suspend fun toggleFavorite(
        collectiveId: Long,
        pageId: Long,
        favorite: Boolean,
    ): ApiResult<Unit>
}
