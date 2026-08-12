package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * WorkManager wrapper around [FullSync]. Runs both as the periodic pull and
 * as the one-shot fired when the app comes to the foreground; the two differ
 * only in how they treat a transient failure (see [KEY_ONE_SHOT]).
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val fullSync: FullSync,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val isOneShot = inputData.getBoolean(KEY_ONE_SHOT, false)
            val outcome = fullSync.run()
            if (outcome is SyncOutcome.Retryable && isOneShot) {
                Timber.w("One-shot sync failed (%s); leaving it to the next foreground", outcome.message)
            }
            return when (retryDecision(outcome, isOneShot)) {
                SyncRetryDecision.Complete -> Result.success()
                SyncRetryDecision.Retry -> Result.retry()
            }
        }

        companion object {
            /**
             * Marks the foreground one-shot. Absent (false) means the periodic
             * run, which keeps WorkManager's retry/backoff semantics.
             */
            const val KEY_ONE_SHOT = "one_shot"
        }
    }

/** What a [SyncOutcome] means for WorkManager. */
internal enum class SyncRetryDecision { Complete, Retry }

/**
 * B-57: a one-shot foreground sync must never park itself in WorkManager's
 * retry backoff. The unique work name is held for the whole backoff window
 * (exponential, capped at five hours), and under the old `KEEP` policy every
 * later `syncNow()` was dropped against it — so a single bad network moment
 * silently disabled foreground sync for hours, which is the "stuck on the
 * state from setup" report. There is nothing to retry *for*: the next
 * foreground fires a fresh one, and the periodic worker covers the
 * background case.
 *
 * Everything else completes. `Failed` means the server answered and refused,
 * which the same request won't fix; `Unauthorised` is the session manager's
 * problem, not the worker's.
 */
internal fun retryDecision(
    outcome: SyncOutcome,
    isOneShot: Boolean,
): SyncRetryDecision =
    when (outcome) {
        is SyncOutcome.Retryable -> if (isOneShot) SyncRetryDecision.Complete else SyncRetryDecision.Retry
        SyncOutcome.Success, SyncOutcome.Unauthorised, is SyncOutcome.Failed -> SyncRetryDecision.Complete
    }
