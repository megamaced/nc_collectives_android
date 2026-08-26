package com.megamaced.nccollectives.data.api

/**
 * Map a failed [ApiResult] to a short user-facing message. Returns null for
 * [ApiResult.Success]; callers handle the success branch themselves.
 */
internal fun ApiResult<*>.userMessage(): String? =
    when (this) {
        is ApiResult.Success<*> -> null
        is ApiResult.NetworkError -> "Couldn't reach the server. Check your connection."
        is ApiResult.HttpError -> httpMessage(code)
        is ApiResult.Unauthorised -> "Session expired — please log in again."
        is ApiResult.Conflict -> "Page changed on the server while you were editing."
        is ApiResult.Unexpected -> cause.message ?: "Unexpected error"
    }

/**
 * B-84: 403 used to render as the bare "Server returned 403", which is both
 * unhelpful and, for the members list, the *common* case rather than an
 * anomaly — Circles answers 403 whenever the signed-in user may not read a
 * team's membership.
 *
 * The wording stops short of "you are not a member" on purpose. Circles
 * returns the same 403 for a circle the user isn't in and for a circle that
 * doesn't exist, and declines to say which; asserting non-membership would
 * be a guess presented as fact. The same arm also covers the other 403s in
 * the app (edit rights revoked mid-session, a share withdrawn), which the
 * "or it may no longer exist" half describes just as well.
 *
 * It cannot do better than this, and the reason is worth recording: the
 * real explanation lives in the response body's `data.message` — `meta.message`
 * is empty on a 403 — but Retrofit throws `HttpException` on any non-2xx
 * before a converter runs, so [apiCall] only ever sees the status line.
 * Reading `data.message` would mean buffering and parsing
 * `HttpException.response()?.errorBody()` in `apiCall`, for every endpoint,
 * to surface a string written for web-app developers. Not worth it for a
 * case whose honest summary is already short.
 */
private fun httpMessage(code: Int): String =
    when (code) {
        403 -> "Access refused (403). You may not have permission, or it may no longer exist."
        else -> "Server returned $code"
    }
