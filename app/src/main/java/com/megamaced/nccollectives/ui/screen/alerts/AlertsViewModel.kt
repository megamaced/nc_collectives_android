package com.megamaced.nccollectives.ui.screen.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.data.auth.AccountBinding
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.AlertKind
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.NotifyLevel
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import com.megamaced.nccollectives.ui.screen.onFailureMessage
import com.megamaced.nccollectives.util.UiText
import com.megamaced.nccollectives.util.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where an alert leads, once its target is in the cache. */
sealed interface AlertTarget {
    /** The visit of the Alerts tab that asked; only that visit may follow it. */
    val visit: Long

    data class Page(
        val pageId: Long,
        override val visit: Long,
    ) : AlertTarget

    data class Collective(
        val collectiveId: Long,
        override val visit: Long,
    ) : AlertTarget
}

/**
 * The Alerts tab: Nextcloud notifications about collective pages, and the
 * per-collective setting for how many there are.
 */
@HiltViewModel
class AlertsViewModel
    @Inject
    constructor(
        private val alertRepository: AlertRepository,
        private val collectiveRepository: CollectiveRepository,
        private val pageRepository: PageRepository,
        accountGeneration: AccountGeneration,
    ) : ViewModel() {
        /**
         * The account this screen was opened under; see `PageViewModel.generation`.
         * Everything the screen starts runs bound to it ([launchOnAccount]):
         * the ids it holds are that account's, and must not reach the next
         * one's server or cache if the screen outlives a switch.
         */
        private val generation = accountGeneration.current()

        private fun launchOnAccount(block: suspend () -> Unit) = viewModelScope.launch(AccountBinding(generation)) { block() }

        /** Alerts swiped away whose dismissal hasn't been answered yet, kept off the list meanwhile. */
        private val dismissing = MutableStateFlow<Set<Long>>(emptySet())

        /** Null until the cache has answered. */
        val alerts: StateFlow<List<Alert>?> =
            combine(alertRepository.observeAlerts(), dismissing) { alerts, hidden ->
                alerts.filterNot { it.id in hidden }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

        /** Whether the server provides notifications; null until it has said. */
        val supported: StateFlow<Boolean?> =
            alertRepository.observeSupported().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

        /** The collectives whose notification level the server reports, so it can be changed here. */
        val notifyCollectives: StateFlow<List<Collective>> =
            collectiveRepository
                .observeCollectives()
                .map { collectives -> collectives.filter { it.notifyLevel != null } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = emptyList(),
                )

        /**
         * Which alerts were new during this visit to the tab. The cache marks
         * them seen as soon as they are shown, so the badge clears; this is
         * what keeps their "new" mark on screen until the user leaves.
         */
        private val _newIds = MutableStateFlow<Set<Long>>(emptySet())
        val newIds: StateFlow<Set<Long>> = _newIds.asStateFlow()

        /** The alert whose target is being fetched before it can open; others wait. */
        private val _opening = MutableStateFlow<Long?>(null)
        val opening: StateFlow<Long?> = _opening.asStateFlow()

        private val _targets = Channel<AlertTarget>(Channel.BUFFERED)
        val targets: Flow<AlertTarget> = _targets.receiveAsFlow()

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        private val _messages = Channel<UiText>(Channel.BUFFERED)
        val messages: Flow<UiText> = _messages.receiveAsFlow()

        private var lastRefreshAt = Long.MIN_VALUE
        private var visits = 0L

        /** A new id each time the tab comes on screen; see [AlertTarget.visit]. */
        fun beginVisit(): Long = ++visits

        /**
         * The tab came to the front: a new visit, so nothing is "new" until
         * this visit shows it; and fetch, unless it did so moments ago.
         */
        fun onResume(now: Long = System.currentTimeMillis()) {
            _newIds.value = emptySet()
            if (lastRefreshAt != Long.MIN_VALUE && now - lastRefreshAt < RESUME_REFRESH_INTERVAL_MS) return
            lastRefreshAt = now
            launchOnAccount { alertRepository.refresh() }
        }

        fun pullToRefresh() {
            if (_isRefreshing.value) return
            _isRefreshing.value = true
            lastRefreshAt = System.currentTimeMillis()
            launchOnAccount {
                val result = alertRepository.refresh()
                _isRefreshing.value = false
                result.onFailureMessage { _messages.trySend(it) }
            }
        }

        /** [shown] is on screen, with the tab in front: remember which were new, then mark exactly those seen. */
        fun onShown(shown: List<Alert>) {
            val unseen = shown.filterNot { it.seen }.map { it.id }
            if (unseen.isEmpty()) return
            _newIds.update { it + unseen }
            launchOnAccount { alertRepository.markSeen(unseen, generation) }
        }

        /**
         * Open what [alert] is about. A page or collective already cached
         * opens at once; otherwise its collective is fetched first, so a
         * mention of a page this device hasn't synced yet opens the page
         * rather than "not found". One at a time.
         */
        fun open(
            alert: Alert,
            visit: Long,
        ) {
            if (_opening.value != null) return
            _opening.value = alert.id
            launchOnAccount {
                try {
                    when (val resolved = resolve(alert, visit)) {
                        is Resolution.Open -> _targets.trySend(resolved.target)
                        is Resolution.Unavailable -> _messages.trySend(resolved.message)
                    }
                } finally {
                    _opening.value = null
                }
            }
        }

        private sealed interface Resolution {
            data class Open(
                val target: AlertTarget,
            ) : Resolution

            data class Unavailable(
                val message: UiText,
            ) : Resolution
        }

        private suspend fun resolve(
            alert: Alert,
            visit: Long,
        ): Resolution {
            if (alert.kind == AlertKind.PageDeleted) {
                val collectiveId = alert.collectiveId ?: return Resolution.Unavailable(uiText(R.string.alert_page_deleted))
                if (!collectiveCached(collectiveId)) {
                    val refreshed = collectiveRepository.refresh()
                    if (!collectiveCached(collectiveId)) return unavailable(refreshed, uiText(R.string.alert_collective_unavailable))
                }
                return Resolution.Open(AlertTarget.Collective(collectiveId, visit))
            }
            if (pageCached(alert.pageId)) return Resolution.Open(AlertTarget.Page(alert.pageId, visit))
            val collectiveId = alert.collectiveId ?: return Resolution.Unavailable(uiText(R.string.alert_page_unavailable))
            if (!collectiveCached(collectiveId)) {
                val refreshed = collectiveRepository.refresh()
                if (!collectiveCached(collectiveId)) return unavailable(refreshed, uiText(R.string.alert_collective_unavailable))
            }
            val refreshed = pageRepository.refresh(collectiveId)
            return if (pageCached(alert.pageId)) {
                Resolution.Open(AlertTarget.Page(alert.pageId, visit))
            } else {
                unavailable(refreshed, uiText(R.string.alert_page_unavailable))
            }
        }

        /** A failed fetch says why; a successful one that didn't find it means it's gone. */
        private fun unavailable(
            fetch: ApiResult<Unit>,
            gone: UiText,
        ): Resolution.Unavailable =
            Resolution.Unavailable(
                when (fetch) {
                    is ApiResult.Success -> gone
                    is ApiResult.NetworkError -> uiText(R.string.alert_open_offline)
                    else -> fetch.userMessage() ?: gone
                },
            )

        private suspend fun pageCached(pageId: Long): Boolean = pageRepository.observePage(pageId).first() != null

        private suspend fun collectiveCached(collectiveId: Long): Boolean =
            collectiveRepository.cachedCollectives().any { it.id == collectiveId && !it.trashed }

        fun dismiss(alertId: Long) {
            dismissing.update { it + alertId }
            launchOnAccount {
                val result = alertRepository.dismiss(alertId)
                // Gone from the cache on success; on failure it comes back.
                dismissing.update { it - alertId }
                if (result !is ApiResult.Success) {
                    _messages.trySend(result.userMessage() ?: uiText(R.string.alert_dismiss_failed))
                }
            }
        }

        fun dismissAll() {
            launchOnAccount {
                val result = alertRepository.dismissAll()
                _messages.trySend(
                    when {
                        result.failed == 0 -> UiText.Plural(R.plurals.alerts_dismissed, result.dismissed)

                        result.dismissed == 0 -> uiText(R.string.alerts_dismiss_all_failed)

                        else -> uiText(
                            R.string.alerts_dismissed_partly,
                            UiText.Plural(R.plurals.alerts_dismissed, result.dismissed),
                            UiText.Plural(R.plurals.alerts_not_dismissed, result.failed),
                        )
                    },
                )
            }
        }

        fun setNotifyLevel(
            collectiveId: Long,
            level: NotifyLevel,
        ) {
            launchOnAccount {
                collectiveRepository.setNotifyLevel(collectiveId, level).onFailureMessage { _messages.trySend(it) }
            }
        }

        private companion object {
            const val RESUME_REFRESH_INTERVAL_MS = 15_000L
        }
    }
