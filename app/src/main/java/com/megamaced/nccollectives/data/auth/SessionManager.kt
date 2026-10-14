package com.megamaced.nccollectives.data.auth

import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
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
        /**
         * `Lazy` breaks the construction cycle: the handler is
         * `AccountSwitcher`, which needs this class. Resolved on the first
         * expiry, from an OkHttp thread — `dagger.Lazy` is safe there, and
         * the same idiom the `Application` uses for the `OkHttpClient`.
         */
        private val expiredSessionHandler: Lazy<ExpiredSessionHandler>,
        private val accountGeneration: AccountGeneration,
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

        /**
         * Session changes — switching, removing the active account, signing
         * in, signing out — run one at a time and each whole: begin, wipe,
         * credentials, settle, publish. Two at once would let the first to
         * finish settle `AccountGeneration` and publish a session while the
         * other is still wiping, opening writes and requests in the middle of
         * a switch.
         */
        private val changeLock = Mutex()

        /** Changes asked for and not yet finished, counted from the moment each is asked for. */
        private val changesRequested = AtomicInteger(0)

        /**
         * Start a session change in [scope]. It is counted at once, so a
         * change already under way knows another follows and leaves landing
         * the session to the last; [begin] shows it as begun at once and
         * again when its turn comes; [body] runs once every change ahead of
         * it has finished.
         */
        fun launchChange(
            scope: CoroutineScope,
            begin: () -> Unit,
            body: suspend () -> Unit,
        ) {
            changesRequested.incrementAndGet()
            begin()
            // A change once counted runs, whatever happens to [scope]: a
            // change ahead may have left landing the session to it. ATOMIC
            // starts it even in a cancelled scope, and NonCancellable carries
            // it through the wait for the lock and through the change itself.
            scope.launch(start = CoroutineStart.ATOMIC) {
                withContext(NonCancellable) {
                    changeLock.withLock {
                        try {
                            begin()
                            body()
                        } finally {
                            // Before the lock is released: the change next in
                            // line can resume inside the unlock, and must not
                            // count this one as still to come.
                            changesRequested.decrementAndGet()
                        }
                    }
                }
            }
        }

        /**
         * Whether the session may land now: true unless another change has
         * been asked for behind the one running, which will land it instead.
         * Landing means settling the account generation and publishing the
         * state that lets the UI and its work start.
         */
        private fun land(): Boolean {
            if (changesRequested.get() > 1) {
                landingDeferred = true
                return false
            }
            landingDeferred = false
            accountGeneration.settle()
            sessionChangeInProgress.set(false)
            refreshState()
            return true
        }

        /**
         * A change ahead left landing the session to a later one. Only read
         * and written under [changeLock], from inside a change.
         */
        @Volatile private var landingDeferred = false

        /**
         * For a change that leaves the live account as it is: land the
         * session if a change ahead of it deferred that, and nothing behind
         * it is waiting to. Returns whether it landed.
         */
        fun landIfDeferred(): Boolean = landingDeferred && land()

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

        /**
         * Re-read the stored accounts without touching the auth state: for a
         * change that leaves the live account as it is.
         */
        fun refreshAccounts() {
            _accounts.value = tokenStore.accounts()
            _activeAccountId.value = tokenStore.activeAccountId()
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
            land()
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
         * Called from `AccountSwitcher` once the incoming account is active
         * (or the outgoing one kept, if the wipe failed). Re-derives the state
         * from the store, so this lands on [AuthState.Unauthenticated] when
         * the switch was really the removal of the last account. Returns
         * whether the session landed — false while another change waits.
         */
        fun endAccountSwitch(): Boolean {
            authFailures.reset()
            return land()
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
        ): Boolean {
            tokenStore.upsertAndActivate(host, loginName, appPassword)
            authFailures.reset()
            return land()
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
