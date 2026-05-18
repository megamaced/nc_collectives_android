package com.megamaced.nccollectives.data.api

/**
 * Map a failed [ApiResult] to a short user-facing message. Returns null for
 * [ApiResult.Success]; callers handle the success branch themselves.
 */
internal fun ApiResult<*>.userMessage(): String? =
    when (this) {
        is ApiResult.Success<*> -> null
        is ApiResult.NetworkError -> "Couldn't reach the server. Check your connection."
        is ApiResult.HttpError -> "Server returned $code"
        is ApiResult.Unauthorised -> "Session expired — please log in again."
        is ApiResult.Conflict -> "Page changed on the server while you were editing."
        is ApiResult.Unexpected -> cause.message ?: "Unexpected error"
    }
