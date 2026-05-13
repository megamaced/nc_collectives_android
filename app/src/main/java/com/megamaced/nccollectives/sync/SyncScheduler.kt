package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val workManager get() = WorkManager.getInstance(context)

        private val connectedConstraints = Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Schedules the recurring 6-hour metadata sync. Idempotent. */
        fun ensurePeriodicSync() {
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
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

        companion object {
            private const val PERIODIC_SYNC = "nc-collectives-sync-periodic"
            private const val ONE_SHOT_SYNC = "nc-collectives-sync-now"
            private const val EDIT_FLUSH = "nc-collectives-edit-flush"
            private const val ATTACHMENT_FLUSH = "nc-collectives-attachment-flush"
            private const val PERIODIC_INTERVAL_HOURS = 6L
        }
    }
