package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.CollectivesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.PagesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.TagsEnvelopeData
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit interface for the Collectives OCS REST API. All paths are relative
 * to the placeholder base URL (`HostInterceptor` rewrites scheme/host/port at
 * request time).
 *
 * Additional endpoints (create/update/delete pages, set emoji/tags, trash,
 * favorites, versions) are introduced in later batches when first consumed.
 */
interface CollectivesApiService {
    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives")
    suspend fun listCollectives(): Envelope<CollectivesEnvelopeData>

    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages")
    suspend fun listPages(
        @Path("collectiveId") collectiveId: Long,
    ): Envelope<PagesEnvelopeData>

    /**
     * Replaces the user's favorite-pages list for [collectiveId]. The
     * `favoritePages` form field is, per the Collectives OpenAPI spec, the
     * JSON-encoded string of a `Long[]` (e.g. `"[12,34]"`).
     */
    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/userSettings/favoritePages")
    suspend fun setFavoritePages(
        @Path("collectiveId") collectiveId: Long,
        @Field("favoritePages") favoritePagesJson: String,
    )

    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}/emoji")
    suspend fun setPageEmoji(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @Field("emoji") emoji: String,
    )

    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/tags")
    suspend fun listTags(
        @Path("collectiveId") collectiveId: Long,
    ): Envelope<TagsEnvelopeData>

    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}/tags/{tagId}")
    suspend fun addPageTag(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @Path("tagId") tagId: Long,
    )

    @DELETE("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}/tags/{tagId}")
    suspend fun removePageTag(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @Path("tagId") tagId: Long,
    )
}
