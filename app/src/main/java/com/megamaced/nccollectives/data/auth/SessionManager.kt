package com.megamaced.nccollectives.data.auth

import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
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
        /**
         * `Lazy` breaks the construction cycle: the handler is
         * `AccountSwitcher`, which needs this class. Resolved on the first
         * expiry, from an OkHttp thread — `dagger.Lazy` is safe there, and
         * the same idiom the `Application` uses for the `OkHttpClient`.
         */
        private val expiredSessionHandler: Lazy<ExpiredSessionHandler>,
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
         * Decides when a run of 401s means the active account's credential
         * is dead. See [AuthFailureTracker] — the policy lives there so it
         * can be tested without `EncryptedSharedPreferences` underneath it.
         */
        private val authFailures = AuthFailureTracker()

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
            authFailures.reset()
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
            authFailures.reset()
            sessionChangeInProgress.set(false)
            refreshState()
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
            authFailures.reset()
            sessionChangeInProgress.set(false)
            refreshState()
        }

        /**
         * Record a response from an authenticated request. Once a run of
         * 401s says the active account's credential is dead, hand it to
         * [ExpiredSessionHandler]. Silently no-ops while a sign-out or
         * account switch is already in progress.
         *
         * Called from [com.megamaced.nccollectives.data.api.AuthInterceptor].
         *
         * Issue #19: this used to call a local `logout()` — `beginSignOut()`
         * + `endSignOut()` with nothing between them — which wiped no local
         * data and cleared the credentials of every account rather than the
         * one the server had rejected. `AuthInterceptor` only ever attaches
         * the *active* account's credential, so a 401 streak is attributable
         * to exactly one account, and that is the only one that should pay
         * for it.
         */
        fun onAuthenticatedResponse(code: Int) {
            if (sessionChangeInProgress.get()) return
            if (!authFailures.onResponse(code)) return
            val expired = tokenStore.activeAccountId()
            if (expired == null) {
                // Nothing to remove — the store emptied under us. Just make
                // sure the observed state agrees with it.
                refreshState()
                return
            }
            Timber.i("Server rejected the active account's credential; expiring it")
            expiredSessionHandler.get().onSessionExpired(expired)
        }
    }
