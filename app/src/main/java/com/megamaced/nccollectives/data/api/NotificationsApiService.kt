package com.megamaced.nccollectives.data.api

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The Notifications app's OCS API, and the capabilities that say whether a
 * server has it. A 404 alone doesn't: a proxy or a broken route answers
 * that too.
 */
interface NotificationsApiService {
    /**
     * Every notification for the user, from every app. Entries stay raw —
     * not even assumed to be objects — so each is parsed on its own, and
     * one this app can't read doesn't take the rest down with it.
     *
     * A [Response] because 204 is an answer in its own right: the server
     * has no apps that notify, so there is nothing to list.
     */
    @GET("ocs/v2.php/apps/notifications/api/v2/notifications")
    suspend fun list(): Response<Envelope<List<JsonElement>>>

    /** The server's capabilities: `notifications.ocs-endpoints` lists what the Notifications app offers. */
    @GET("ocs/v2.php/cloud/capabilities")
    suspend fun capabilities(): Envelope<JsonElement>

    /**
     * Dismiss one notification. There is a delete-all endpoint as well, and
     * it is deliberately not here: it would dismiss every app's
     * notifications, not just the ones this app shows.
     */
    @DELETE("ocs/v2.php/apps/notifications/api/v2/notifications/{id}")
    suspend fun delete(
        @Path("id") id: Long,
    ): Envelope<JsonElement>
}
