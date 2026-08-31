package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.share.SharePayloadHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the multi-step sign-out flow. Runs on a singleton-scoped
 * supervisor scope so the work completes even when the caller's
 * `viewModelScope` is torn down as the scaffold swaps to `LoginScreen`.
 *
 * Order of operations (B-5, post-audit):
 *  1. `sessionManager.beginSignOut()` flips `authState` to
 *     `Unauthenticated` and arms the 401-suppression flag so any
 *     in-flight `SyncWorker` / `EditFlushWorker` requests that race
 *     against the wipe can't trigger a second sign-out cycle. The
 *     scaffold observes the state change and mounts `LoginScreen`
 *     on the next frame — observers of Room flows are torn down
 *     before the DB transaction below begins.
 *  2. [LocalDataWiper] cancels the WorkManager jobs and removes every
 *     trace of the account's data: Room, DataStore, the attachment cache
 *     directory, Coil's caches, and the WebView's own storage.
 *  3. `sessionManager.endSignOut()` clears the encrypted token store —
 *     *every* account, not just the active one — and releases the
 *     401-suppression flag.
 *
 * Signing out is all-accounts by design: it is the "this is not my phone
 * any more" action. Removing one account and keeping the rest is
 * [AccountSwitcher.removeAccount].
 */
@Singleton
class LogoutHandler
    @Inject
    constructor(
        private val sessionManager: SessionManager,
        private val localDataWiper: LocalDataWiper,
        private val sharePayloadHolder: SharePayloadHolder,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun signOut() {
            sessionManager.beginSignOut()
            // S-16: drop any pending share payload before the next session
            // starts. A share intent captured under user A would otherwise
            // still be sitting in the process-wide singleton when user B
            // signs in on the same install, and would pop the share UI
            // with user A's payload targeting user B's Nextcloud.
            sharePayloadHolder.consume()
            scope.launch {
                localDataWiper.wipe(keepDevicePreferences = false)
                sessionManager.endSignOut()
            }
        }
    }
