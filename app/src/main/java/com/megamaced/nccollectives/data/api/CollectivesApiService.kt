package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.AttachmentsEnvelopeData
import com.megamaced.nccollectives.data.api.dto.CollectiveEnvelopeData
import com.megamaced.nccollectives.data.api.dto.CollectivesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.PageEnvelopeData
import com.megamaced.nccollectives.data.api.dto.PagesEnvelopeData
import com.megamaced.nccollectives.data.api.dto.TagEnvelopeData
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
import retrofit2.http.Query

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

    /**
     * Create a new collective (Batch 22). The server atomically creates the
     * underlying Nextcloud Team (Circle) and the `.Collectives/<name>/`
     * folder. Emoji is optional and the only metadata that can be set at
     * creation time besides the name — and the name is then fixed forever
     * (the server has no rename endpoint).
     */
    @FormUrlEncoded
    @POST("ocs/v2.php/apps/collectives/api/v1.0/collectives")
    suspend fun createCollective(
        @Field("name") name: String,
        @Field("emoji") emoji: String?,
    ): Envelope<CollectiveEnvelopeData>

    /**
     * Set the collective's emoji. **The Collectives API has no name-change
     * endpoint** — `PUT /collectives/{id}` accepts `{emoji}` only
     * (`ENDPOINTS.md` gotcha #3, verified against Collectives 4.4.0).
     * Permission-level controls live on `/editLevel` and `/shareLevel`
     * sub-paths and are out of scope for Batch 22.
     */
    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}")
    suspend fun setCollectiveEmoji(
        @Path("collectiveId") collectiveId: Long,
        @Field("emoji") emoji: String,
    ): Envelope<CollectiveEnvelopeData>

    /**
     * Soft-delete a collective. Moves it to the collectives trash; the
     * collective remains restorable via [restoreTrashedCollective] until
     * [permanentlyDeleteCollective] is called. Mirrors the page-trash
     * model — same two-step flow.
     */
    @DELETE("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}")
    suspend fun trashCollective(
        @Path("collectiveId") collectiveId: Long,
    ): Envelope<CollectiveEnvelopeData>

    @GET("ocs/v2.php/apps/collectives/api/v1.0/collectives/trash")
    suspend fun listTrashedCollectives(): Envelope<CollectivesEnvelopeData>

    /**
     * Restore a trashed collective. Method is `PATCH` per the live-tested
     * `ENDPOINTS.md` reference, matching the page-trash restore endpoint
     * (`restoreTrashedPage`).
     */
    @PATCH("ocs/v2.php/apps/collectives/api/v1.0/collectives/trash/{collectiveId}")
    suspend fun restoreTrashedCollective(
        @Path("collectiveId") collectiveId: Long,
    ): Envelope<CollectiveEnvelopeData>

    /**
     * Permanently delete a trashed collective. Irreversible.
     *
     * `?circle=true` also tears down the underlying Nextcloud Team — the
     * Android UI always passes `true` because we don't expose the
     * Team-as-separate-entity concept anywhere; leaving the Team behind
     * with no collective attached would leak membership state that the
     * user can no longer manage from inside this app.
     */
    @DELETE("ocs/v2.php/apps/collectives/api/v1.0/collectives/trash/{collectiveId}")
    suspend fun permanentlyDeleteCollective(
        @Path("collectiveId") collectiveId: Long,
        @Query("circle") circle: Boolean = true,
    ): Envelope<CollectiveEnvelopeData>

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

    /**
     * Duplicate a page (Batch 23). Same update endpoint as [updatePage] —
     * `{copy: true}` server-side triggers the duplicate codepath. Supports
     * leaf and folder pages. An optional `title` overrides the copy's
     * title (defaults to "<original> (copy)" server-side). Returns the
     * newly-created page.
     */
    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{pageId}")
    suspend fun copyPage(
        @Path("collectiveId") collectiveId: Long,
        @Path("pageId") pageId: Long,
        @Field("copy") copy: Boolean = true,
    ): Envelope<PageEnvelopeData>

    /**
     * Set the explicit child-page ordering for [parentPageId] (Batch 23).
     * Body field `subpageOrder` is a **JSON-stringified array of subpage
     * IDs** (`"[12,34,56]"`), mirroring the favorites endpoint's
     * JSON-string-in-form-field convention. Verified against the OpenAPI
     * spec's `page-set-subpage-order` operation.
     */
    @FormUrlEncoded
    @PUT("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/pages/{parentPageId}/subpageOrder")
    suspend fun setSubpageOrder(
        @Path("collectiveId") collectiveId: Long,
        @Path("parentPageId") parentPageId: Long,
        @Field("subpageOrder") subpageOrderJson: String,
    ): Envelope<PageEnvelopeData>

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

    /**
     * Create a new tag on [collectiveId]. **Color must be 6 hex chars
     * without a `#` prefix** (e.g. `"2d7d46"`) — `ENDPOINTS.md` gotcha
     * #2: the column is `varchar(6)`, sending `"#2d7d46"` causes a DB
     * overflow. Returns the created tag with its server-assigned id.
     */
    @FormUrlEncoded
    @POST("ocs/v2.php/apps/collectives/api/v1.0/collectives/{collectiveId}/tags")
    suspend fun createTag(
        @Path("collectiveId") collectiveId: Long,
        @Field("name") name: String,
        @Field("color") color: String,
    ): Envelope<TagEnvelopeData>

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
