package com.megamaced.nccollectives.data.auth

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.sync.SyncScheduler
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
 *  2. Cancel every WorkManager job we own so the workers don't fire
 *     against the user's now-empty cache.
 *  3. Wipe every Room table in a single transaction.
 *  4. Clear the user's DataStore preferences (recent searches, theme,
 *     cadence). Keeping prefs across user accounts would leak the
 *     previous user's recent searches.
 *  5. `sessionManager.endSignOut()` clears the encrypted token store
 *     and releases the 401-suppression flag.
 */
@Singleton
class LogoutHandler
    @Inject
    constructor(
        private val database: NcCollectivesDatabase,
        private val sessionManager: SessionManager,
        private val syncScheduler: SyncScheduler,
        private val userPreferences: UserPreferences,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun signOut() {
            sessionManager.beginSignOut()
            scope.launch {
                syncScheduler.cancelAll()
                database.withTransaction {
                    database.attachmentDao().clear()
                    database.editQueueDao().clear()
                    database.pageDao().clear()
                    database.collectiveDao().clear()
                }
                userPreferences.clearAll()
                sessionManager.endSignOut()
            }
        }
    }
