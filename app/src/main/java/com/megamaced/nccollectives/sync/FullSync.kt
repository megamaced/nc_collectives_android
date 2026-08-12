package com.megamaced.nccollectives.sync

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a full metadata sync. */
sealed interface SyncOutcome {
    data object Success : SyncOutcome

    /** The network dropped mid-run. Worth trying again unchanged. */
    data class Retryable(
        val message: String,
    ) : SyncOutcome

    /**
     * The session is gone. `SessionManager`'s consecutive-401 streak — not
     * this class — decides when that becomes a sign-out, so callers just
     * stop quietly.
     */
    data object Unauthorised : SyncOutcome

    /** The server answered and refused. Retrying the same call won't help. */
    data class Failed(
        val message: String,
    ) : SyncOutcome
}

/**
 * The pull half of syncing: refresh the collective list, then page metadata
 * for every collective. Page *bodies* are deliberately not pulled here —
 * that would mean downloading every page's markdown on a schedule. Bodies
 * revalidate when a page is opened, via
 * [com.megamaced.nccollectives.domain.repository.PageRepository.refreshBodyIfChanged].
 *
 * Extracted from `SyncWorker` (B-59) so the Settings "Sync now" button runs
 * the same code path as the background worker instead of a second,
 * subtly-different copy of the loop. Also the only place the sync status
 * Settings displays is written.
 */
@Singleton
class FullSync
    @Inject
    constructor(
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
        private val userPreferences: UserPreferences,
    ) {
        suspend fun run(): SyncOutcome {
            val outcome = pull()
            val now = System.currentTimeMillis()
            when (outcome) {
                SyncOutcome.Success -> userPreferences.recordSyncSuccess(now)
                // Deliberately not stamped as a failure: a sign-out is either
                // imminent (which wipes DataStore anyway) or the streak hasn't
                // tripped yet, and "sync failed: unauthorised" is not
                // something the user can act on from the Settings screen.
                SyncOutcome.Unauthorised -> Unit
                is SyncOutcome.Retryable -> userPreferences.recordSyncFailure(now, outcome.message)
                is SyncOutcome.Failed -> userPreferences.recordSyncFailure(now, outcome.message)
            }
            return outcome
        }

        private suspend fun pull(): SyncOutcome {
            var retryable: String? = null
            var failure: String? = null

            when (val listResult = collectiveRepository.refresh()) {
                is ApiResult.Success -> Unit
                is ApiResult.NetworkError -> {
                    Timber.w("Sync deferred: collective refresh hit network error")
                    return SyncOutcome.Retryable(listResult.userMessage() ?: NETWORK_MESSAGE)
                }
                ApiResult.Unauthorised -> {
                    Timber.w("Sync aborted: unauthorised")
                    return SyncOutcome.Unauthorised
                }
                // Anything else: carry on against the cached collective list.
                // One bad list response shouldn't cost us the page refresh for
                // collectives we already know about — but it does mean this
                // run can't be reported as clean, or the status line would
                // claim a full sync we didn't manage.
                else -> {
                    Timber.w("Sync: collective refresh failed, continuing from cache")
                    failure = listResult.userMessage() ?: "Couldn't refresh the collective list"
                }
            }

            // R-30: snapshot read, not a Flow subscription. The previous
            // `.observeCollectives().first()` started a collection just
            // to fetch one value and unsubscribe.
            val collectives = collectiveRepository.cachedCollectives()
            for (collective in collectives) {
                when (val pages = pageRepository.refresh(collective.id)) {
                    is ApiResult.Success -> Unit
                    is ApiResult.NetworkError -> retryable = pages.userMessage() ?: NETWORK_MESSAGE
                    is ApiResult.HttpError -> {
                        Timber.w(
                            "Sync HTTP %d on collective %d: %s",
                            pages.code,
                            collective.id,
                            pages.message,
                        )
                        failure = pages.userMessage() ?: "Server error ${pages.code}"
                    }
                    ApiResult.Unauthorised -> return SyncOutcome.Unauthorised
                    ApiResult.Conflict -> Unit // not meaningful on a GET
                    is ApiResult.Unexpected -> {
                        Timber.w(pages.cause, "Sync unexpected error on collective %d", collective.id)
                        failure = pages.userMessage() ?: "Unexpected error"
                    }
                }
            }
            return when {
                retryable != null -> SyncOutcome.Retryable(retryable)
                failure != null -> SyncOutcome.Failed(failure)
                else -> SyncOutcome.Success
            }
        }

        private companion object {
            const val NETWORK_MESSAGE = "Couldn't reach the server"
        }
    }
