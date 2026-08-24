package com.megamaced.nccollectives.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

/**
 * Show [message] once, then tell the ViewModel it has been seen (R-62).
 *
 * Keyed on the message, so a new one interrupts the one on screen and a
 * repeat of a cleared message shows again. That keying is also the trap:
 * [onConsumed] clears the key, so anything placed after it would be
 * cancelled by its own clear — nothing may be added below it. A screen that
 * has to *act* on the dismissal (an undo, a navigation) keeps its own effect
 * keyed on something the action doesn't touch — see `PageViewScreen`'s
 * trash/undo — and a ViewModel that would rather not park one-shot messages
 * in state at all uses [SnackbarMessageEffect].
 */
@Composable
fun SnackbarStatusEffect(
    message: String?,
    host: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        host.showSnackbar(message)
        onConsumed()
    }
}

/**
 * Show every message [messages] emits, in order — for a ViewModel that
 * hands out one-shot messages through a channel instead of parking them in
 * state, because its screen can be disposed mid-snackbar and never clear
 * them. `CollectiveListViewModel.messages` (B-79) is why that exists.
 */
@Composable
fun SnackbarMessageEffect(
    messages: Flow<String>,
    host: SnackbarHostState,
) {
    LaunchedEffect(messages, host) {
        messages.collect { message -> host.showSnackbar(message) }
    }
}
