package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.AttachmentsEnvelopeData
import com.megamaced.nccollectives.data.api.dto.CollectivesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.PageEnvelopeData
import com.megamaced.nccollectives.data.api.dto.PagesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.TagsEnvelopeData
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
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
     * Single-page metadata fetch. Useful for picking up changes after a
     * mutation that doesn't return the full object, or as a fallback when
     * the page list hasn't re-indexed yet. Per spec gotcha #16, may 404
     * briefly after a move while the indexer catches up.
     */
    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}")
    suspend fun getPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
    ): Envelope<PageEnvelopeData>

    /**
     * Create a new page under [parentPageId]. Pass the landing-page id to
     * create at the root of the collective. The server handles indexing,
     * filesystem naming, and folder promotion of the parent (a previously
     * leaf parent is converted to a folder atomically). Body content is
     * not part of this endpoint — set the markdown body separately via
     * the WebDAV PUT to the new page's path.
     */
    @FormUrlEncoded
    @POST("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{parentPageId}")
    suspend fun createPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("parentPageId") parentPageId: Long,
        @Field("title") title: String,
    ): Envelope<PageEnvelopeData>

    /**
     * One-stop rename / move / copy / reorder endpoint. The [params] map
     * accepts any combination of `title`, `parentId`, `index`, `copy`
     * (per spec). Use specific helpers below for typed call sites; the
     * `@FieldMap` form keeps `null` fields out of the form body without
     * needing separate Retrofit methods.
     *
     * Note (spec gotcha #16): a move/rename can change the page's
     * Nextcloud file id. `GET /pages/{id}` may briefly 404 against the
     * new id until reindex. Callers should refresh the collective via
     * `listPages` to reconcile.
     */
    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}")
    suspend fun updatePage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @FieldMap params: Map<String, String>,
    ): Envelope<PageEnvelopeData>

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

    /**
     * Soft-delete a page. The page moves to the per-collective trash; its
     * markdown file becomes inaccessible at the original path. Restore /
     * purge endpoints below operate on the trashed page.
     */
    @DELETE("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}")
    suspend fun trashPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
    )

    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/trash")
    suspend fun listTrashedPages(
        @Path("collectiveId") collectiveId: Long,
    ): Envelope<PagesEnvelopeData>

    /**
     * Restore a trashed page to its original location. The HTTP method is
     * `PATCH` (not `PUT` or `POST`) per the live-tested `ENDPOINTS.md`
     * reference from `collectives-mcp` — Collectives 4.4.0 routes this
     * verb only. Prior to Batch 18g this used `@PUT` and restore was
     * silently broken against current servers (B-28).
     */
    @PATCH("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/trash/{pageId}")
    suspend fun restoreTrashedPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
    )

    /** Permanently delete a trashed page. Irreversible. */
    @DELETE("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/trash/{pageId}")
    suspend fun purgeTrashedPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
    )

    /**
     * Per-page attachments metadata. Replaces the WebDAV PROPFIND in
     * Batch 12 (OCS-3). Returns typed JSON; server-side ids let
     * [deleteAttachment] target by stable id rather than user-typed
     * filename.
     *
     * Spec gotcha #9: response has no `pageId` field — callers track
     * it from their own context.
     */
    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}/attachments")
    suspend fun listAttachments(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
    ): Envelope<AttachmentsEnvelopeData>

    /**
     * Delete an attachment by its server id (OCS-4). More robust than
     * the previous WebDAV DELETE by filename — survives renames.
     */
    @DELETE(
        "ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}/attachments/{attachmentId}",
    )
    suspend fun deleteAttachment(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @Path("attachmentId") attachmentId: Long,
    )
}
