package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.DirectEditingCapabilityEnvelopeData
import com.megamaced.nccollectives.data.api.dto.DirectEditingOpenEnvelopeData
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for the Nextcloud Files `directEditing` OCS API.
 * Rides the same authenticated `OkHttpClient` as `CollectivesApiService`
 * — the `OCS-APIRequest: true` header is added by `AuthInterceptor` and
 * the host is rewritten by `HostInterceptor`.
 *
 * Upstream controller: `apps/files/lib/Controller/DirectEditingController.php`.
 *
 * Server requirement: Nextcloud Server ≥ 18 for the API to exist; Text
 * app ≥ 2.x for it to be registered as an editor for `text/markdown`.
 * Callers must defend against an older server by handling 404 / empty
 * `editors` map gracefully — see `DirectEditingRepository.isAvailable()`.
 */
interface DirectEditingService {
    @GET("ocs/v2.php/apps/files/api/v1/directEditing")
    suspend fun getCapability(): Envelope<DirectEditingCapabilityEnvelopeData>

    /**
     * Open a directediting session for the file at [path]. Path is the
     * server-relative path under the user's Files area (e.g.
     * `.Collectives/Wiki/Some Folder/Page.md`) — **not** a full WebDAV
     * URL. Returns a fully-qualified URL with a one-shot token in the
     * query string; the WebView loads this verbatim.
     *
     * The token is consumed on first load and short-lived. Reloading
     * (config change, process death) must re-request a fresh URL via
     * this endpoint.
     */
    @FormUrlEncoded
    @POST("ocs/v2.php/apps/files/api/v1/directEditing/open")
    suspend fun openSession(
        @Field("path") path: String,
        @Field("editorId") editorId: String? = null,
    ): Envelope<DirectEditingOpenEnvelopeData>
}
