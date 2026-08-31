package com.megamaced.nccollectives.data.auth

import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adding, switching and removing Nextcloud accounts (issue #14).
 *
 * **One account's data is cached at a time.** Switching wipes the outgoing
 * account's local state and re-syncs the incoming one, rather than keeping
 * both in Room simultaneously. The credentials of every account are kept,
 * which is the part the issue actually asked for — swapping accounts
 * without signing out and typing a server URL again.
 *
 * Caching both at once would mean an `accountId` column on every table and
 * a primary-key rewrite: `PageEntity` and `CollectiveEntity` key on the raw
 * *server* id, and two servers will happily both have a page 17. It would
 * also mean two `directediting` sessions sharing the WebView's single
 * cookie jar. Neither is needed to fix the reported problem, so neither is
 * here.
 *
 * The cost is a re-sync on each switch, which is why [pendingEditCount]
 * exists: queued writes that have not reached the server do not survive the
 * wipe, and the user is told how many before they commit to it.
 */
@Singleton
class AccountSwitcher
    @Inject
    constructor(
        private val sessionManager: SessionManager,
        private val tokenStore: TokenStore,
        private val localDataWiper: LocalDataWiper,
        private val syncScheduler: SyncScheduler,
        private val sharePayloadHolder: SharePayloadHolder,
        private val database: NcCollectivesDatabase,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Edits queued locally that the server has not accepted yet. They are
         * lost by the wipe a switch performs, so the confirmation says how
         * many there are — the same care `ManageAccountsViewModel` takes in
         * the Nextcloud Notes client before it drops an account.
         *
         * Counts conflicted rows too: the user has not resolved those either,
         * and losing one silently is the same surprise.
         */
        suspend fun pendingEditCount(): Int = database.editQueueDao().countAll()

        /**
         * Make [accountId] the live account, wiping whatever the previous one
         * had cached.
         *
         * No-ops when it is already active or names no stored account, so a
         * double tap or a switch racing a removal cannot strand the user in
         * [AuthState.Switching].
         */
        fun switchTo(accountId: String) {
            if (accountId == tokenStore.activeAccountId()) return
            if (tokenStore.accounts().none { it.id == accountId }) {
                Timber.w("Ignoring a switch to an account that is no longer stored")
                return
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon("switching account")) return@launch
                tokenStore.setActiveAccount(accountId)
                finishSwitch()
            }
        }

        /**
         * Complete a login: store the credential, and make it live.
         *
         * The single entry point for *both* the cold sign-in and the "add
         * another account" flow, which is what lets `LoginScreen` stay
         * mode-less. Which of the three things happens is decided from the
         * store, not from a flag the caller passes:
         *
         *  - nothing signed in → no cache to wipe, straight to authenticated.
         *  - re-authenticating the account that is already active → rewrite
         *    the app password and keep the cache. A revoked app password is
         *    re-established without the user losing their offline copy.
         *  - anything else → a switch, with the wipe.
         */
        fun signInTo(
            host: String,
            loginName: String,
            appPassword: String,
        ) {
            val incomingId = accountIdOf(host, loginName)
            val activeId = tokenStore.activeAccountId()
            if (activeId == null || activeId == incomingId) {
                sessionManager.onLoginSuccess(host, loginName, appPassword)
                // The periodic job is cancelled by every wipe and only
                // scheduled from `Application.onCreate`, so without this a
                // sign-in later in the same process leaves background sync
                // switched off until the app is next cold-started.
                syncScheduler.reschedulePeriodic()
                syncScheduler.syncNow()
                return
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon("adding an account")) return@launch
                tokenStore.upsertAndActivate(host, loginName, appPassword)
                finishSwitch()
            }
        }

        /**
         * Forget one account, keeping the others.
         *
         * Removing an account that is not the active one costs nothing —
         * none of its data is on the device. Removing the active one is a
         * switch to whichever account remains, or a sign-out if it was the
         * last.
         */
        fun removeAccount(accountId: String) {
            if (tokenStore.accounts().none { it.id == accountId }) return
            if (accountId != tokenStore.activeAccountId()) {
                tokenStore.removeAccount(accountId)
                sessionManager.refreshState()
                return
            }
            beginSwitch()
            scope.launch {
                if (!wipeOrAbandon("removing an account")) return@launch
                val nextActive = tokenStore.removeAccount(accountId)
                if (nextActive == null) {
                    // That was the last account. `endAccountSwitch` re-derives
                    // the state from an empty store, which lands on
                    // `Unauthenticated` and shows the login screen.
                    Timber.i("Removed the last account; signing out")
                }
                finishSwitch()
            }
        }

        /**
         * Run the wipe, and put the session back the way it was if it fails.
         *
         * The alternative — carrying on to activate the incoming account —
         * would leave the outgoing account's pages, attachments and queued
         * writes in Room while the app is authenticated as somebody else,
         * which is precisely the cross-account leak the wipe exists to
         * prevent. Abandoning keeps the invariant "whatever is cached belongs
         * to the active account", because the active account never changed.
         *
         * Returns true when the caller should carry on. Failure here is
         * pathological — a corrupted database, a full disk — so the recovery
         * is to stay put and log rather than to build a UI for it; the user
         * sees the switch simply not happen.
         */
        private suspend fun wipeOrAbandon(what: String): Boolean {
            val result = runCatching { localDataWiper.wipe(keepDevicePreferences = true) }
            result.exceptionOrNull()?.let { failure ->
                Timber.e(failure, "Local wipe failed while %s; staying on the current account", what)
                // Re-derives from the store, which still names the outgoing
                // account, so this lands back on `Authenticated`.
                sessionManager.endAccountSwitch()
                return false
            }
            return true
        }

        private fun beginSwitch() {
            sessionManager.beginAccountSwitch()
            // S-16, restated for switching: a share intent captured against
            // the outgoing account must not be replayed into the incoming
            // one's Nextcloud.
            sharePayloadHolder.consume()
        }

        private fun finishSwitch() {
            sessionManager.endAccountSwitch()
            if (tokenStore.activeAccountId() == null) return
            // Nothing is cached for the incoming account, so the app would
            // otherwise open on an empty collective list until the next
            // foreground or scheduled sync.
            syncScheduler.reschedulePeriodic()
            syncScheduler.syncNow()
        }
    }
