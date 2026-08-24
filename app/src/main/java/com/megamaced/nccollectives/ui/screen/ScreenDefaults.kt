package com.megamaced.nccollectives.ui.screen

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.userMessage

/**
 * How long a screen ViewModel's `stateIn` keeps its upstream alive after the
 * last collector goes away (R-58). Long enough to ride out a configuration
 * change without re-querying Room and DataStore, short enough that leaving
 * the screen releases those observers.
 */
internal const val STOP_TIMEOUT_MS = 5_000L

/**
 * Pass the user-facing message to [onMessage] when this result failed; do
 * nothing when it succeeded.
 *
 * R-59: [userMessage] is null for [ApiResult.Success] and non-null for every
 * failure arm, so the `if (result !is ApiResult.Success)` half of the pair
 * written at ~30 call sites was saying the same thing twice. The type check
 * earned its keep only where a success must not clear a message something
 * else has set — which is what this expresses.
 */
internal inline fun ApiResult<*>.onFailureMessage(onMessage: (String) -> Unit) {
    val message = userMessage()
    if (message != null) onMessage(message)
}
