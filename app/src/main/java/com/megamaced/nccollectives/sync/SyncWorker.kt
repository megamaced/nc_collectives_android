package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Periodic pull: refresh the collective list, then refresh page metadata
 * for every collective the user has access to. Page bodies remain
 * lazy-fetched on view to avoid pulling potentially large amounts of
 * markdown the user may never open.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val collectivesResult = collectiveRepository.refresh()
            if (collectivesResult is ApiResult.NetworkError) {
                Timber.w("Sync deferred: collective refresh hit network error")
                return Result.retry()
            }
            if (collectivesResult is ApiResult.Unauthorised) {
                Timber.w("Sync aborted: unauthorised")
                // The interceptor already ticked SessionManager's consecutive-401
                // counter; a sustained outage will flip the user to LoginScreen
                // via that mechanism rather than via this worker. We just bail.
                return Result.success()
            }

            // R-30: snapshot read, not a Flow subscription. The previous
            // `.observeCollectives().first()` started a collection just
            // to fetch one value and unsubscribe.
            val collectives = collectiveRepository.cachedCollectives()
            var hadRetryableFailure = false
            for (collective in collectives) {
                when (val pages = pageRepository.refresh(collective.id)) {
                    is ApiResult.Success -> Unit
                    is ApiResult.NetworkError -> hadRetryableFailure = true
                    is ApiResult.HttpError -> Timber.w(
                        "Sync HTTP %d on collective %d: %s",
                        pages.code,
                        collective.id,
                        pages.message,
                    )
                    ApiResult.Unauthorised -> {
                        // Same rationale as the early-exit above — let the
                        // SessionManager 401-streak drive any sign-out.
                        return Result.success()
                    }
                    ApiResult.Conflict -> Unit // not meaningful on a GET
                    is ApiResult.Unexpected -> Timber.w(pages.cause, "Sync unexpected error on collective %d", collective.id)
                }
            }
            return if (hadRetryableFailure) Result.retry() else Result.success()
        }
    }
