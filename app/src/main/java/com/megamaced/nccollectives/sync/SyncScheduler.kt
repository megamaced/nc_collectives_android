package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.megamaced.nccollectives.data.prefs.SyncCadence
import com.megamaced.nccollectives.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

        /**
         * Bootstrap the periodic-sync schedule. Call once from
         * `Application.onCreate`. Replaces the previous split between
         * `init { … drop(1) collect }` and `ensurePeriodicSync()` which
         * raced on cold start (B-52): DataStore can emit twice
         * (empty-state → loaded-state) and `drop(1)` could skip the wrong
         * emission, after which the next emit re-ran `applyCadence` with
         * `UPDATE` and reset the timer that `ensurePeriodicSync` had just
         * set with `KEEP`. This sequenced version reads the current
         * cadence once with `KEEP`, then collects subsequent *distinct*
         * changes with `UPDATE`.
         */
        fun start() {
            scope.launch {
                val cadenceFlow = userPreferences.flow.map { it.syncCadence }.distinctUntilChanged()
                val initial = cadenceFlow.first()
                applyCadence(initial, replaceExisting = false)
                cadenceFlow.drop(1).collect { applyCadence(it, replaceExisting = true) }
            }
        }

        /**
         * Fires a one-shot metadata sync, e.g. on app foreground.
         *
         * B-57: `REPLACE`, not `KEEP`. Under `KEEP` an earlier run still
         * holding the unique name — parked in retry backoff, or wedged
         * against a server that accepts the connection and then stalls —
         * swallowed every subsequent request, so foreground sync silently
         * stopped happening. Superseding is the right call for a sync: the
         * newer request does strictly the same work with fresher intent, and
         * `SyncWorker` is idempotent, so cancelling a run mid-flight costs
         * nothing but the wasted request.
         *
         * That last clause is what makes `REPLACE` safe *here* and unsafe on
         * the two flushes below — see [flushEditsWhenOnline].
         */
        fun syncNow() {
            val oneShot = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connectedConstraints)
                .setInputData(workDataOf(SyncWorker.KEY_ONE_SHOT to true))
                .build()
            workManager.enqueueUniqueWork(ONE_SHOT_SYNC, ExistingWorkPolicy.REPLACE, oneShot)
        }

        /**
         * Schedules a one-shot edit-queue flush; runs as soon as the network
         * is up.
         *
         * B-64: `APPEND_OR_REPLACE`, not `REPLACE`. B-57's argument for
         * superseding turns entirely on `SyncWorker` being an idempotent
         * `GET`; this worker issues `PUT`s. `ProcessLifecycleOwner.onStart`
         * fires this on every app foreground, so under `REPLACE` a user
         * switching apps mid-flush cancelled a `PUT` the server had already
         * accepted — the DB bookkeeping that records the new ETag never ran,
         * and the next run refetched a changed ETag and flagged a conflict
         * against the user's *own* successful write.
         *
         * `APPEND_OR_REPLACE` keeps the property B-57 actually needed — a
         * request is never silently dropped, and a cancelled or failed
         * sequence is replaced rather than blocking newcomers behind a dead
         * unique name — while letting an in-flight write finish first.
         * `EditFlushWorker` additionally makes its post-`PUT` bookkeeping
         * `NonCancellable`, since logout's [cancelAll] and WorkManager's own
         * stop signals can still land mid-write.
         *
         * The cost of appending is that a request queued behind one parked in
         * retry backoff waits for it. That is tolerable here in a way it
         * wasn't for B-57: both requests drain the same `edit_queue` rows, so
         * the parked run's own retry does the newer request's work when it
         * fires. Nothing is dropped, only deferred.
         */
        fun flushEditsWhenOnline() {
            val oneShot = OneTimeWorkRequestBuilder<EditFlushWorker>()
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniqueWork(EDIT_FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, oneShot)
        }

        /**
         * Schedules a one-shot attachment upload flush; runs as soon as the
         * network is up. `APPEND_OR_REPLACE` for the same reason as
         * [flushEditsWhenOnline] — the uploads are `PUT`s, and a cancelled
         * one leaves the row stranded mid-status with its staged bytes still
         * on disk.
         */
        fun flushAttachmentUploadsWhenOnline() {
            val oneShot = OneTimeWorkRequestBuilder<AttachmentUploadWorker>()
                .setConstraints(connectedConstraints)
                .build()
            workManager.enqueueUniqueWork(ATTACHMENT_FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, oneShot)
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
