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
 * Order of operations:
 *  1. Cancel every WorkManager job we own so a sync doesn't run mid-wipe.
 *  2. Wipe every Room table in a single transaction.
 *  3. Clear the user's DataStore preferences (recent searches, theme,
 *     cadence). Re-applying defaults on next login feels right; the
 *     alternative — keeping prefs across user accounts — leaks the
 *     previous user's recent searches.
 *  4. Clear the encrypted token store and flip `authState` to
 *     `Unauthenticated`. The scaffold's auth gate observes this and
 *     swaps in `LoginScreen` on the next frame.
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
            scope.launch {
                syncScheduler.cancelAll()
                database.withTransaction {
                    database.attachmentDao().clear()
                    database.editQueueDao().clear()
                    database.pageDao().clear()
                    database.collectiveDao().clear()
                }
                userPreferences.clearAll()
                sessionManager.logout()
            }
        }
    }
