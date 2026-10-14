package com.megamaced.nccollectives.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface UserApiService {
    /**
     * The signed-in user, as the server knows them. [CurrentUserDto.id] is
     * the internal user id — what Collectives records as a page's last
     * editor — which is not necessarily the login name the app signed in
     * with: an email login, or an OIDC account whose id is a hash.
     */
    @GET("ocs/v2.php/cloud/user")
    suspend fun currentUser(): Envelope<CurrentUserDto>
}

@Serializable
data class CurrentUserDto(
    val id: String,
)
