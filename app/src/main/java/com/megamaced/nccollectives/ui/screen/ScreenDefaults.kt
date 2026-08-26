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

/**
 * Whether a failed read is worth offering a Retry button for (R-66).
 *
 * The members strip and the members screen each grew their own copy of this
 * rule and the copies disagreed — one called every [ApiResult.HttpError]
 * terminal, the other only 403 — so the same failure offered Retry on one
 * surface and not the other. This is the single answer, and it follows the
 * taxonomy `EditFlushWorker.flushFailureAction` already applies to the sync
 * path so the app classifies a status the same way wherever it lands.
 *
 * 403 is the sharp case and the reason this exists at all: it is the
 * *expected* answer for a non-member, it is indistinguishable from "that team
 * does not exist", and every Circles endpoint carries brute-force protection
 * that throttles the caller's own IP on repeated permission failures. A Retry
 * button there invites the user to get themselves throttled.
 *
 * 408 and 429 are retryable because they are explicitly "ask again"; the rest
 * of 4xx is the server saying the request itself is wrong, which repeating
 * cannot fix. 5xx is transient, except 507 (insufficient storage), which
 * needs the *server* to change before anything else will.
 */
internal fun isRetryableFailure(result: ApiResult<*>): Boolean =
    when (result) {
        // Nothing to retry, and no button to offer.
        is ApiResult.Success -> false

        // Never reached the server, so nothing has been decided.
        is ApiResult.NetworkError -> true

        is ApiResult.HttpError -> when {
            result.code == 408 || result.code == 429 -> true
            result.code == 507 -> false
            result.code in 400..499 -> false
            else -> true
        }

        // Session handling owns this; a Retry here would just fail again.
        is ApiResult.Unauthorised -> false

        is ApiResult.Conflict -> false

        is ApiResult.Unexpected -> false
    }
