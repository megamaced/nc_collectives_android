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

    /**
     * An account switch is in progress (issue #14). Distinct from
     * [Unauthenticated] because the user has not signed out and must not be
     * shown the login screen; distinct from [Unknown] because the scaffold
     * says so rather than showing a bare spinner.
     *
     * Load-bearing as well as cosmetic: the scaffold unmounts the whole
     * authenticated host on this state, which tears down every Room flow
     * observer *before* `AccountSwitcher` wipes the tables underneath them
     * — the same ordering `LogoutHandler` relies on.
     */
    data object Switching : AuthState
}

@Singleton
class SessionManager
    @Inject
    constructor(
        private val tokenStore: TokenStore,
    ) {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        private val _accounts = MutableStateFlow<List<AccountSummary>>(emptyList())

        /** Every account on the device. Drives the switcher in Settings. */
        val accounts: StateFlow<List<AccountSummary>> = _accounts.asStateFlow()

        private val _activeAccountId = MutableStateFlow<String?>(null)
        val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

        /**
         * Set while [LogoutHandler] is wiping local state, or while
         * `AccountSwitcher` is swapping accounts. Suppresses
         * `AuthInterceptor`'s 401-driven sign-out, so any in-flight
         * `SyncWorker` / `EditFlushWorker` requests that race with the wipe
         * don't trigger a second (concurrent) sign-out cycle.
         */
        private val sessionChangeInProgress = AtomicBoolean(false)

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
            _accounts.value = tokenStore.accounts()
            _activeAccountId.value = tokenStore.activeAccountId()
            _authState.value = if (tokenStore.getCredentials() != null) {
                AuthState.Authenticated
            } else {
                AuthState.Unauthenticated
            }
        }

        /** Called from [LogoutHandler] before it touches local state. */
        fun beginSignOut() {
            sessionChangeInProgress.set(true)
            _authState.value = AuthState.Unauthenticated
        }

        /** Called from [LogoutHandler] once the local wipe is complete. */
        fun endSignOut() {
            tokenStore.clear()
            consecutive401s.set(0)
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Called from `AccountSwitcher` before it wipes the outgoing
         * account's cache. Unlike [beginSignOut] this must not flip to
         * [AuthState.Unauthenticated]: the user has not signed out, and a
         * flash of the login screen mid-switch reads as one.
         */
        fun beginAccountSwitch() {
            sessionChangeInProgress.set(true)
            _authState.value = AuthState.Switching
        }

        /**
         * Called from `AccountSwitcher` once the incoming account is active.
         * Re-derives the state from the store, so this lands on
         * [AuthState.Unauthenticated] when the switch was really the removal
         * of the last account.
         */
        fun endAccountSwitch() {
            consecutive401s.set(0)
            sessionChangeInProgress.set(false)
            refreshState()
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

        /**
         * Persist a freshly obtained credential and make it the live one.
         *
         * Only correct as the *cold* sign-in path — there is no cached data
         * to wipe when nothing was signed in. Adding a second account, or
         * re-authenticating while another account's cache is loaded, goes
         * through `AccountSwitcher.signInTo`, which owns the wipe decision.
         */
        fun onLoginSuccess(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            tokenStore.upsertAndActivate(host, loginName, appPassword)
            consecutive401s.set(0)
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Record a response from an authenticated request. A 2xx resets the
         * counter; an `Unauthorised` ticks it and, once we cross the
         * threshold, flips the session to `Unauthenticated`. Silently
         * no-ops while a sign-out or account switch is already in progress.
         *
         * Called from [com.megamaced.nccollectives.data.api.AuthInterceptor].
         */
        fun onAuthenticatedResponse(code: Int) {
            if (sessionChangeInProgress.get()) return
            if (code == 401) {
                val n = consecutive401s.incrementAndGet()
                if (n >= CONSECUTIVE_401_THRESHOLD) {
                    logout()
                }
            } else {
                // B-51: reset on *any* non-401, not just 2xx. The previous
                // `code in 200..299` branch meant a transient `401 → 500 →
                // 401` sequence (e.g. flaky reverse proxy) would still
                // sign the user out — the 5xx wasn't evidence of a working
                // auth exchange but also wasn't evidence of an invalid
                // token. Only stack consecutive 401s.
                consecutive401s.set(0)
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
