package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.util.UiText

/**
 * A short user-facing message for a failed [ApiResult]: its [failure],
 * worded. Null for [ApiResult.Success]; callers handle the success branch
 * themselves.
 */
internal fun ApiResult<*>.userMessage(): UiText? = failure()?.toUiText()
