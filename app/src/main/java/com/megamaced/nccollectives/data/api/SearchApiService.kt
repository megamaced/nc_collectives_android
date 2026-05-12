package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.SearchEnvelopeData
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Nextcloud unified search, scoped to the Collectives `collectives-pages`
 * provider. Returns OCS-wrapped entries.
 */
interface SearchApiService {
    @GET("ocs/v2.php/search/providers/collectives-pages/search")
    suspend fun searchPages(
        @Query("term") term: String,
        @Query("limit") limit: Int = 25,
    ): Envelope<SearchEnvelopeData>
}
