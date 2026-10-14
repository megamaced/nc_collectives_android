package com.megamaced.nccollectives.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.AccountBinding
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.domain.repository.AlertRepository
import com.megamaced.nccollectives.ui.screen.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The navigation bar's Alerts badge, and the refresh that keeps it current
 * while the app is in use. The background sync refreshes alerts too; this
 * covers the user coming back to the app between syncs.
 */
@HiltViewModel
class AlertBadgeViewModel
    @Inject
    constructor(
        private val alertRepository: AlertRepository,
        private val accountGeneration: AccountGeneration,
    ) : ViewModel() {
        val unseenCount: StateFlow<Int> =
            alertRepository.observeUnseenCount().stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = 0,
            )

        private var lastRefreshAt = Long.MIN_VALUE

        /**
         * The account [lastRefreshAt] is for. This view model sits outside
         * the authenticated navigation host and outlives an account switch,
         * so a refresh for the outgoing account mustn't hold off the
         * incoming one's.
         */
        private var lastRefreshFor: Long? = null

        /** On coming to the foreground; at most once a minute per account, so flicking between apps costs nothing. */
        fun refreshIfStale(now: Long = System.currentTimeMillis()) {
            val generation = accountGeneration.current()
            if (generation == lastRefreshFor && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
            lastRefreshAt = now
            lastRefreshFor = generation
            viewModelScope.launch(AccountBinding(generation)) { alertRepository.refresh() }
        }

        private companion object {
            const val REFRESH_INTERVAL_MS = 60_000L
        }
    }

/** The badge's text: the count, capped so it fits the badge. */
internal fun badgeText(count: Int): String = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()

private const val MAX_BADGE = 99
