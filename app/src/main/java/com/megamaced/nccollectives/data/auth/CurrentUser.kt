package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.UserApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.prefs.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who the signed-in user is to the server: their internal user id, which is
 * what Collectives records as a page's last editor.
 *
 * Not the login name. Login Flow v2 hands back the name the user typed,
 * which may be an email address, and an OIDC account's internal id may be a
 * hash; comparing a page's last editor against either would claim pages
 * that aren't the user's, or miss ones that are. So [id] stays null until
 * the server has said, and what depends on it waits.
 */
@Singleton
class CurrentUser
    @Inject
    constructor(
        private val api: UserApiService,
        private val userPreferences: UserPreferences,
        private val accountGeneration: AccountGeneration,
    ) {
        val id: Flow<String?> = userPreferences.currentUserId

        /**
         * Ask the server once per account. Best effort: a failure is logged
         * and leaves [id] null, to be asked again next time.
         */
        suspend fun refreshIfUnknown() {
            // Captured before the first suspension, not after it, and the
            // outer one when there is one (a sync): a switch landing during
            // the read below would otherwise hand this call the incoming
            // account's generation.
            accountGeneration.bound { generation -> refreshIfUnknownBound(generation) }
        }

        private suspend fun refreshIfUnknownBound(generation: Long) {
            if (userPreferences.currentUserId.first() != null) return
            if (!accountGeneration.isCurrent(generation)) return
            when (val result = apiCall { api.currentUser() }) {
                is ApiResult.Success -> {
                    userPreferences.setCurrentUserId(result.data.ocs.data.id) {
                        accountGeneration.isCurrent(generation)
                    }
                }

                else -> {
                    Timber.i("Couldn't look up the current user: %s", result)
                }
            }
        }
    }
