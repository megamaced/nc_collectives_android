package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val userPreferences: UserPreferences,
    ) {
        private val workManager get() = WorkManager.getInstance(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val connectedConstraints = Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        init {
            // Reschedule the periodic job whenever the user changes their
            // cadence preference. WorkManager handles the "Off" case by
            // cancelling the unique work name.
            scope.launch {
                userPreferences.flow
                    .distinctUntilChangedBy { it.syncCadence }
                    .collect { prefs ->
                        applyCadence(prefs.syncCadence, replaceExisting = true)
                    }
            }
        }

        /**
         * Schedules the recurring metadata sync at the user's preferred cadence.
         * Idempotent — the cadence flow above keeps it in sync after the user
         * changes the preference.
         */
        fun ensurePeriodicSync() {
            scope.launch {
                val cadence = currentCadence()
                applyCadence(cadence, replaceExisting = false)
            }
        }

        /** Fires a one-shot metadata sync, e.g. on app foreground. */
        fun syncNow() {
            val oneShot = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniqueWork(ONE_SHOT_SYNC, ExistingWorkPolicy.KEEP, oneShot)
        }

        /** Schedules a one-shot edit-queue flush; runs as soon as the network is up. */
        fun flushEditsWhenOnline() {
            val oneShot = OneTimeWorkRequestBuilder<EditFlushWorker>()
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniqueWork(EDIT_FLUSH, ExistingWorkPolicy.REPLACE, oneShot)
        }

        /** Schedules a one-shot attachment upload flush; runs as soon as the network is up. */
        fun flushAttachmentUploadsWhenOnline() {
            val oneShot = OneTimeWorkRequestBuilder<AttachmentUploadWorker>()
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniqueWork(ATTACHMENT_FLUSH, ExistingWorkPolicy.REPLACE, oneShot)
        }

        /**
         * Cancels every WorkManager job this scheduler owns — used by the
         * logout flow so background workers don't fire against a stale
         * session.
         */
        fun cancelAll() {
            workManager.cancelUniqueWork(PERIODIC_SYNC)
            workManager.cancelUniqueWork(ONE_SHOT_SYNC)
            workManager.cancelUniqueWork(EDIT_FLUSH)
            workManager.cancelUniqueWork(ATTACHMENT_FLUSH)
        }

        private suspend fun currentCadence(): SyncCadence = userPreferences.flow.map { it.syncCadence }.first()

        private fun applyCadence(
            cadence: SyncCadence,
            replaceExisting: Boolean,
        ) {
            val hours = cadence.hours
            if (hours == null) {
                workManager.cancelUniqueWork(PERIODIC_SYNC)
                return
            }
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_SYNC,
                if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        companion object {
            private const val PERIODIC_SYNC = "nc-collectives-sync-periodic"
            private const val ONE_SHOT_SYNC = "nc-collectives-sync-now"
            private const val EDIT_FLUSH = "nc-collectives-edit-flush"
            private const val ATTACHMENT_FLUSH = "nc-collectives-attachment-flush"
        }
    }
