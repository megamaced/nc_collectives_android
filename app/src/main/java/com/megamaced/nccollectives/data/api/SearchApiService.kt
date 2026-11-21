package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.SearchEnvelopeData
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Nextcloud unified search, using the Collectives title and content providers. */
interface SearchApiService {
    @GET("ocs/v2.php/search/providers/collectives-pages/search")
    suspend fun searchPages(
        @Query("term") term: String,
        @Query("limit") limit: Int = 25,
    ): Envelope<SearchEnvelopeData>

    // Collectives 4.8.0 returns SearchResult::complete (isPaginated=false, cursor=null).
    @GET("ocs/v2.php/search/providers/collectives-page-content/search")
    suspend fun searchPageContent(
        @Query("term") term: String,
        @Query("limit") limit: Int,
    ): Response<Envelope<SearchEnvelopeData>>
}
