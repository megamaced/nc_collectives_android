package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Unknown : AuthState

    data object Authenticated : AuthState

    data object Unauthenticated : AuthState
}

@Singleton
class SessionManager
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        /**
         * Set while [LogoutHandler] is wiping local state. Suppresses
         * `AuthInterceptor`'s 401-driven sign-out, so any in-flight
         * `SyncWorker` / `EditFlushWorker` requests that race with the wipe
         * don't trigger a second (concurrent) sign-out cycle.
         */
        private val signOutInProgress = AtomicBoolean(false)

        /**
         * Count of consecutive 401 responses on authenticated requests. A 2xx
         * resets it. Requires at least [CONSECUTIVE_401_THRESHOLD] in a row
         * before we treat the session as truly invalid — see B-2 in the audit
         * findings: a single 401 from a proxy / shared resource / transient
         * Nextcloud blip used to log the user out and lose any in-flight
         * saves.
         */
        private val consecutive401s = AtomicInteger(0)

        init {
            refreshState()
        }

        fun refreshState() {
            _authState.value = if (tokenStore.getCredentials() != null) {
                AuthState.Authenticated
            } else {
                AuthState.Unauthenticated
            }
        }

        /** Called from [LogoutHandler] before it touches local state. */
        fun beginSignOut() {
            signOutInProgress.set(true)
            _authState.value = AuthState.Unauthenticated
        }

        /** Called from [LogoutHandler] once the local wipe is complete. */
        fun endSignOut() {
            tokenStore.clear()
            consecutive401s.set(0)
            signOutInProgress.set(false)
        }

        /**
         * Legacy entry point — used by tests and the user-driven Sign Out
         * flow. Equivalent to begin + end with no work in between. Prefer
         * the [LogoutHandler] for the full multi-step wipe.
         */
        fun logout() {
            beginSignOut()
            endSignOut()
        }

        fun onLoginSuccess(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            tokenStore.saveCredentials(host, loginName, appPassword)
            consecutive401s.set(0)
            signOutInProgress.set(false)
            _authState.value = AuthState.Authenticated
        }

        /**
         * Record a response from an authenticated request. A 2xx resets the
         * counter; an `Unauthorised` ticks it and, once we cross the
         * threshold, flips the session to `Unauthenticated`. Silently
         * no-ops while sign-out is already in progress.
         *
         * Called from [com.megamaced.nccollectives.data.api.AuthInterceptor].
         */
        fun onAuthenticatedResponse(code: Int) {
            if (signOutInProgress.get()) return
            when {
                code == 401 -> {
                    val n = consecutive401s.incrementAndGet()
                    if (n >= CONSECUTIVE_401_THRESHOLD) {
                        logout()
                    }
                }
                code in 200..299 -> consecutive401s.set(0)
            }
        }

        private companion object {
            // Two in a row before we treat the session as invalid. Picks up
            // genuine token rejection on the next failure while ignoring a
            // single transient proxy 401 or a 401 from a non-Collectives
            // resource the user happens to have requested.
            const val CONSECUTIVE_401_THRESHOLD = 2
        }
    }
