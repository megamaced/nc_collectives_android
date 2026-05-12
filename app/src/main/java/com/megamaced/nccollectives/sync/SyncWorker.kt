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
import kotlinx.coroutines.flow.first
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
                return Result.success() // SessionManager will surface this to the UI
            }

            val collectives = collectiveRepository.observeCollectives().first()
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
                    ApiResult.Unauthorised -> return Result.success()
                    ApiResult.Conflict -> Unit // not meaningful on a GET
                    is ApiResult.Unexpected -> Timber.w(pages.cause, "Sync unexpected error on collective %d", collective.id)
                }
            }
            return if (hadRetryableFailure) Result.retry() else Result.success()
        }
    }
