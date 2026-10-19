package com.megamaced.nccollectives.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Taps on a tab whose root is already showing. That root screen answers by
 * going back to its top; Search's also focuses its field.
 *
 * A tap while a screen above the root is showing is not one of these: it
 * pops back to the root instead (see [reselectTab]).
 */
internal class TabReselections {
    private val taps = MutableSharedFlow<TopLevelTab>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // One flow per tab, made once, so a screen's effect keyed on it isn't
    // restarted by every recomposition.
    private val perTab: Map<TopLevelTab, Flow<Unit>> =
        TopLevelTab.entries.associateWith { tab -> taps.filter { it == tab }.map { } }

    fun tapped(tab: TopLevelTab) {
        taps.tryEmit(tab)
    }

    fun of(tab: TopLevelTab): Flow<Unit> = perTab.getValue(tab)
}

/**
 * Back to the first item. Only while the list shows items: a list that
 * isn't laid out, because an empty state is showing instead, would hold
 * the scroll until it is.
 */
internal suspend fun LazyListState.scrollToTop() {
    if (layoutInfo.totalItemsCount > 0) animateScrollToItem(0)
}

/**
 * Runs [action] each time [reselected] fires, for as long as the caller is
 * composed. Touching the list cancels the coroutine an animated scroll runs
 * in, so each run gets its own: the cancellation ends that run, not the
 * listening. A new tap replaces a run still going.
 */
@Composable
internal fun OnReselected(
    reselected: Flow<Unit>,
    action: suspend () -> Unit,
) {
    val latest by rememberUpdatedState(action)
    LaunchedEffect(reselected) { reselected.collectLatest { latest() } }
}
